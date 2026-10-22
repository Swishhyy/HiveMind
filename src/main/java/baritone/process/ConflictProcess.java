/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.behavior.IBehavior;
import baritone.api.event.events.TickEvent;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.pathing.goals.GoalNear;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.bot.BotWork;
import baritone.utils.bot.SurvivalPolicy;
import baritone.utils.social.CombatPolicy;
import baritone.utils.social.MeleeCombat;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import baritone.utils.social.GoalRetreat;
import baritone.utils.social.SocialPolicy;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Temporary retreat takes priority over bot work, while the user's pause/stop controls retain priority. */
public final class ConflictProcess extends BaritoneProcessHelper implements IBehavior {
    private record Threat(BlockPos position, int distance, long expires) {}
    private final Map<UUID, Threat> threats = new LinkedHashMap<>();
    private Level observedWorld;
    private boolean active;
    private int quietTicks;
    private int failedPaths;
    private UUID attackerId,protectedVictim;
    private long defendUntil;
    private long stalkRetreatRetryAt;
    private boolean protectingEscort;
    private boolean ownsShield;
    private int previousSlot = -1;
    private UUID mobTarget;

    public ConflictProcess(Baritone baritone) { super(baritone); }
    @Override public boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.1; }

    private boolean hasWork() { return BotWork.active(baritone); }
    public boolean holdsUse() { return ownsShield && baritone.getPathingControlManager().mostRecentInControl().orElse(null) == this; }

    public void onDamage(DamageSource source) {
        if (!Baritone.settings().fleeConflicts.value || ctx.player() == null || ctx.world() == null || !hasWork()) return;
        Entity attacker = source.getEntity();
        if (attacker == null) attacker = source.getDirectEntity();
        if (attacker == null || attacker == ctx.player() || baritone.getNetwork().isTeammate(attacker.getUUID()) || baritone.getNetwork().team().alliedExplosive(attacker)) return; // Environmental damage is not evidence that a bystander attacked.
        if (baritone.getStalkProcess().handlesCombatAgainst(attacker)) return;
        if (observedWorld != ctx.world()) { onLostControl(); observedWorld = ctx.world(); }
        quietTicks = 0;
        protectingEscort = false;protectedVictim=null;
        if (!attacker.getUUID().equals(attackerId)) { attackerId = attacker.getUUID(); defendUntil = System.currentTimeMillis() + 10000; }
        remember(attacker, true);
        begin();
    }

    public void onProtectedDamage(int victimId, DamageSource source) {
        UUID escorted = baritone.getCompanionProcess().escortedPlayer();
        if (!Baritone.settings().fleeConflicts.value||ctx.world()==null||ctx.player()==null) return;
        Entity victim = ctx.world().getEntity(victimId);
        boolean teammate=victim!=null&&baritone.getNetwork().isTeammate(victim.getUUID());
        if(!teammate&&(escorted==null||!Baritone.settings().escortDefendOwner.value))return;
        Entity attacker = source.getEntity() == null ? source.getDirectEntity() : source.getEntity();
        if (victim == null || !teammate&&!escorted.equals(victim.getUUID()) || victim.distanceToSqr(ctx.player()) > 256
                || attacker == null || attacker == ctx.player() || baritone.getNetwork().isTeammate(attacker.getUUID()) || baritone.getNetwork().team().alliedExplosive(attacker) || attacker.getUUID().equals(escorted)) return;
        if(baritone.getStalkProcess().handlesCombatAgainst(attacker))return;
        if (observedWorld != ctx.world()) { onLostControl(); observedWorld = ctx.world(); }
        if (!attacker.getUUID().equals(attackerId)) { attackerId = attacker.getUUID(); defendUntil = System.currentTimeMillis() + 10000; }
        protectingEscort = true;protectedVictim=victim.getUUID(); quietTicks = 0; remember(attacker, true); begin();
    }

    private void remember(Entity entity, boolean attacked) {
        if (threats.size() >= 32 && !threats.containsKey(entity.getUUID())) threats.remove(threats.keySet().iterator().next());
        int distance = SocialPolicy.fleeDistance(Baritone.settings().conflictFleeDistance.value, isType(entity, "warden"));
        Threat previous = threats.get(entity.getUUID());
        if (previous != null) distance = Math.max(distance, previous.distance());
        if (attacked) distance = SocialPolicy.attackedDistance(distance,
                Math.hypot(entity.getX() - ctx.player().getX(), entity.getZ() - ctx.player().getZ()));
        threats.put(entity.getUUID(), new Threat(entity.blockPosition().immutable(), distance, System.currentTimeMillis() + 15000));
    }

    private void begin() {
        if (active) return;
        active = true;
        quietTicks = failedPaths = 0;
        baritone.getDepositProcess().suspendForConflict();
        baritone.getWorkstationProcess().suspendForConflict();
        if (ctx.player().containerMenu != ctx.player().inventoryMenu && ctx.player().containerMenu.getCarried().isEmpty()) ctx.player().closeContainer();
        baritone.getInputOverrideHandler().clearAllKeys();
        ctx.playerController().resetBlockRemoving();
        baritone.getPathingBehavior().softCancelIfSafe();
        logDirect("Conflict detected; retreating before resuming bot work");
    }

    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null) {
            onLostControl();
            observedWorld = null;
            return;
        }
        if (observedWorld != ctx.world()) {
            onLostControl();
            observedWorld = ctx.world();
        }
        if (!Baritone.settings().fleeConflicts.value || (!active && !hasWork())) {
            onLostControl();
            return;
        }
        if (ownsShield && !holdsUse()) releaseShield();
        long now = System.currentTimeMillis();
        Map<UUID, Entity> loaded = new LinkedHashMap<>();
        ctx.entitiesStream().forEach(entity -> loaded.put(entity.getUUID(), entity));
        threats.entrySet().removeIf(entry -> entry.getValue().expires() < now || baritone.getNetwork().isTeammate(entry.getKey())
                ||loaded.containsKey(entry.getKey())&&!loaded.get(entry.getKey()).isAlive());
        for (var entry : new ArrayList<>(threats.entrySet())) {
            Entity entity = loaded.get(entry.getKey());
            if (entity != null) {
                Threat old = entry.getValue();
                double keepDistance=entity instanceof net.minecraft.world.entity.player.Player&&baritone.getFlight().escaping()?Math.max(512,old.distance()):old.distance();
                boolean close = Math.hypot(entity.getX() - ctx.player().getX(), entity.getZ() - ctx.player().getZ()) < keepDistance;
                threats.put(entry.getKey(), new Threat(entity.blockPosition().immutable(), old.distance(), close ? now + 15000 : old.expires()));
            }
        }
        int radius = Math.max(4, Math.min(32, Baritone.settings().conflictMobRadius.value));
        // An engaged hunt does not break off for nearby mobs; it retreats on low health or a second player instead.
        boolean engaged = baritone.getStalkProcess().committedCombat();
        for (Entity entity : loaded.values()) {
            if (engaged || ctx.player().isFallFlying() || !entity.isAlive() || !(entity instanceof Mob mob) || entity.distanceToSqr(ctx.player()) > radius * radius) continue;
            boolean neutral = isType(entity, "enderman") || isType(entity, "zombified_piglin") || isType(entity, "piglin");
            if ((entity.getType().getCategory() == MobCategory.MONSTER && !neutral) || mob.getTarget() == ctx.player()) remember(entity, false);
        }
        if (!threats.isEmpty()) begin();
        if(active&&playerThreat()){
            releaseShield();baritone.getFlight().escape(this,threats.values().stream().map(Threat::position).toList());
        }
    }

    public boolean playerThreat(){
        return ctx.world().players().stream().anyMatch(player->player.isAlive()&&!player.isSpectator()&&threats.containsKey(player.getUUID()));
    }

    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || !ctx.player().isAlive()) {
            onLostControl();
            return null;
        }
        if(playerThreat())baritone.getFlight().escape(this,threats.values().stream().map(Threat::position).toList());
        PathingCommand mobFight=tryFightMob(isSafeToCancel);
        if(mobFight!=null)return mobFight;
        if(!playerThreat()&&mobTarget==null&&attackerId!=null&&MeleeCombat.weapon(ctx,false)<0)
            baritone.getFlight().escape(this,threats.values().stream().map(Threat::position).toList());
        if (System.currentTimeMillis() < stalkRetreatRetryAt) {
            if (isSafeToCancel) baritone.getInputOverrideHandler().clearAllKeys();
            tryDefend(isSafeToCancel);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (calcFailed && ++failedPaths >= 3) {
            if (baritone.getStalkProcess().isActive() && !baritone.getStalkProcess().hunting()) {
                failedPaths = 0;
                stalkRetreatRetryAt = System.currentTimeMillis() + 5000;
                logDirect("Retreat route blocked; keeping the stalk target and retrying the escape");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("No safe retreat path was found; stopping bot work");
            stopTasks();
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (tryDefend(isSafeToCancel)) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        PathingCommand approach = escortDefenseApproach(calcFailed, isSafeToCancel);
        if (approach != null) return approach;
        releaseShield();
        List<GoalRetreat.Threat> positions = threats.values().stream()
                .map(threat -> new GoalRetreat.Threat(threat.position(), threat.distance())).toList();
        GoalRetreat goal = positions.isEmpty() ? null : new GoalRetreat(positions);
        boolean separated = goal == null || goal.isInGoal(ctx.playerFeet());
        if (separated) quietTicks++;
        else quietTicks = 0;
        if (SocialPolicy.resumeAfterThreat(separated, quietTicks)) {
            onLostControl();
            baritone.getInputOverrideHandler().clearAllKeys();
            logDirect("Clear of the conflict; resuming bot work");
            return null;
        }
        if (separated) {
            if (isSafeToCancel) baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand tryFightMob(boolean safe){
        if(!Baritone.settings().botFightHostileMobs.value||playerThreat()||baritone.getSculkProcess().isActive()||baritone.getFlight().escaping())return null;
        java.util.stream.Stream<? extends Entity> nearby=Baritone.settings().botEntityAwareness.value?baritone.getAwareness().mobs().stream():ctx.entitiesStream();
        var candidates=nearby.filter(entity->entity instanceof Mob&&entity.isAlive()&&threats.containsKey(entity.getUUID())
                &&entity.distanceToSqr(ctx.player())<=20*20).map(entity->(Mob)entity).toList();
        boolean dangerous=candidates.stream().anyMatch(entity->isType(entity,"warden")||isType(entity,"creeper")||isType(entity,"wither")||isType(entity,"ender_dragon"));
        if(safe&&MeleeCombat.prepare(ctx,false))return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        int weapon=MeleeCombat.weapon(ctx,false);
        if(!CombatPolicy.fightMob(weapon>=0,ctx.player().getHealth()+ctx.player().getAbsorptionAmount(),ctx.player().getFoodData().getFoodLevel(),candidates.size(),dangerous,false)){
            mobTarget=null;return null;
        }
        Mob target=candidates.stream().min(java.util.Comparator.comparingDouble(entity->entity.getUUID().equals(mobTarget)?-1:entity.distanceToSqr(ctx.player()))).orElse(null);
        if(target==null){mobTarget=null;return null;}mobTarget=target.getUUID();
        if(!ctx.player().hasLineOfSight(target)||target.distanceToSqr(ctx.player())>8){releaseShield();return new PathingCommand(new GoalNear(target.blockPosition(),1),PathingCommandType.REVALIDATE_GOAL_AND_PATH);}
        if(!safe||ctx.minecraft().gameMode==null)return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
        if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(weapon);ctx.playerController().syncHeldItem();
        if(MeleeCombat.aim(ctx,baritone,target)&&ctx.player().getAttackStrengthScale(0)>=.95f){
            stopShieldUse();
            if(baritone.getNetwork().team().clearStrike(target)){ctx.minecraft().gameMode.attack(ctx.player(),target);ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);}
        }else if(ctx.player().getOffhandItem().is(Items.SHIELD)){
            ownsShield=true;if(!ctx.player().isUsingItem())ctx.playerController().processRightClick(ctx.player(),ctx.world(),InteractionHand.OFF_HAND);
        }
        return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
    }

    private boolean tryDefend(boolean safe) {
        if (!safe || !"defend".equalsIgnoreCase(Baritone.settings().botConflictMode.value)
                || attackerId == null || System.currentTimeMillis() > defendUntil || baritone.getSculkProcess().isActive()) return false;
        Entity target = ctx.entitiesStream().filter(entity -> attackerId.equals(entity.getUUID())).findFirst().orElse(null);
        if (!(target instanceof LivingEntity) || !target.isAlive() || target.distanceToSqr(ctx.player()) > 8
                || !ctx.player().hasLineOfSight(target)) return false;
        if (baritone.getNetwork().isTeammate(target.getUUID()) || target instanceof net.minecraft.world.entity.player.Player && baritone.getBotControl().isTrusted(target.getUUID())) return false;
        int nearby = (int) threats.values().stream().filter(threat -> threat.position().distSqr(ctx.playerFeet()) < 10 * 10).count();
        if (!SurvivalPolicy.mayDefend(ctx.player().getHealth(), ctx.player().getFoodData().getFoodLevel(), nearby,
                isType(target, "warden"), isType(target, "creeper"), true)) return false;
        if(MeleeCombat.prepare(ctx,((LivingEntity)target).isBlocking()))return true;
        int weapon = MeleeCombat.weapon(ctx,((LivingEntity)target).isBlocking());
        if (weapon < 0 || ctx.minecraft().gameMode == null) return false;
        baritone.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
        if (previousSlot < 0) previousSlot = ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(weapon); ctx.playerController().syncHeldItem();
        boolean aimed=MeleeCombat.aim(ctx,baritone,(LivingEntity)target);
        if (aimed && ctx.player().getAttackStrengthScale(0) >= 0.95f) {
            stopShieldUse();
            ctx.player().getInventory().setSelectedSlot(weapon); ctx.playerController().syncHeldItem();
            if (!baritone.getNetwork().team().clearStrike(target)) return false;
            ctx.minecraft().gameMode.attack(ctx.player(), target);
            ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        } else if (ctx.player().getOffhandItem().is(Items.SHIELD)) {
            ownsShield = true;
            if (!ctx.player().isUsingItem()) ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.OFF_HAND);
        }
        return true;
    }
    private PathingCommand escortDefenseApproach(boolean failed, boolean safe) {
        if (!protectingEscort || failed || !safe || !"defend".equalsIgnoreCase(Baritone.settings().botConflictMode.value)
                || System.currentTimeMillis() > defendUntil || baritone.getSculkProcess().isActive()) return null;
        UUID escorted = protectedVictim;
        Entity owner = ctx.entitiesStream().filter(entity -> entity.getUUID().equals(escorted)).findFirst().orElse(null);
        Entity target = ctx.entitiesStream().filter(entity -> entity.getUUID().equals(attackerId)).findFirst().orElse(null);
        if (owner == null || !(target instanceof LivingEntity) || !target.isAlive() || target.distanceToSqr(owner) > 64
                || target.distanceToSqr(ctx.player()) > 256 || target.distanceToSqr(ctx.player()) <= 8
                || !ctx.player().hasLineOfSight(target) || baritone.getNetwork().isTeammate(target.getUUID()) || (target instanceof net.minecraft.world.entity.player.Player && baritone.getBotControl().isTrusted(target.getUUID()))) return null;
        int nearby = (int) threats.values().stream().filter(threat -> threat.position().distSqr(ctx.playerFeet()) < 256).count();
        if (!SurvivalPolicy.mayDefend(ctx.player().getHealth(), ctx.player().getFoodData().getFoodLevel(), nearby,
                isType(target, "warden"), isType(target, "creeper"), true)) return null;
        boolean armed = false;
        for (int i = 0; i < 9; i++) {
            var stack = ctx.player().getInventory().getItem(i);
            if ((stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES))
                    && (!stack.isDamageableItem() || stack.getMaxDamage()-stack.getDamageValue() > Math.max(1,Baritone.settings().botToolReserve.value))) armed = true;
        }
        if (!armed) return null;
        releaseShield();
        return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private void stopShieldUse() {
        boolean release = ownsShield; ownsShield = false;
        if (release && ctx.player() != null && ctx.player().isUsingItem() && ctx.minecraft().gameMode != null) ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
    }
    private void releaseShield() {
        stopShieldUse();
        if (ctx.player() != null) {
            if (previousSlot >= 0) { ctx.player().getInventory().setSelectedSlot(previousSlot); if (ctx.minecraft().gameMode != null) ctx.playerController().syncHeldItem(); }
        }
        previousSlot = -1;
    }
    private void stopTasks() {
        baritone.getJobManager().fail("No safe retreat path was found");
        baritone.getSurvivalProcess().onLostControl();
        baritone.getSculkProcess().onLostControl();
        baritone.getMineProcess().cancel();
        baritone.getCompanionProcess().onLostControl();
        baritone.getWorkstationProcess().onLostControl();
        baritone.getRepairProcess().onLostControl();
        baritone.getDepositProcess().onLostControl();
        baritone.getStalkProcess().onLostControl();
        baritone.getFollowProcess().onLostControl();
        baritone.getCustomGoalProcess().onLostControl();
        baritone.getBuilderProcess().onLostControl();
        baritone.getExploreProcess().onLostControl();
        baritone.getFarmProcess().onLostControl();
        baritone.getGetToBlockProcess().onLostControl();
        baritone.getPortalTravelProcess().onLostControl();
    }
    private static boolean isType(Entity entity, String path) {
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && id.getNamespace().equals("minecraft") && id.getPath().equals(path);
    }
    @Override public void onLostControl() { releaseShield(); active = protectingEscort = false;protectedVictim=mobTarget=null; quietTicks = failedPaths = 0; threats.clear(); attackerId = protectedVictim = null; defendUntil = stalkRetreatRetryAt = 0; }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return "Retreating from conflict"; }
}

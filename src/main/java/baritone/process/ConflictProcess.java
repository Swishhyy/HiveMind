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
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.bot.BotWork;
import baritone.utils.bot.SurvivalPolicy;
import baritone.api.utils.RotationUtils;
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
    private UUID attackerId;
    private long defendUntil;
    private boolean ownsShield;
    private int previousSlot = -1;
    private int attackDelay;

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
        if (attacker == null || attacker == ctx.player()) return; // Environmental damage is not evidence that a bystander attacked.
        if (observedWorld != ctx.world()) { onLostControl(); observedWorld = ctx.world(); }
        quietTicks = 0;
        attackerId = attacker.getUUID(); defendUntil = System.currentTimeMillis() + 10000;
        remember(attacker, true);
        begin();
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
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) ctx.player().closeContainer();
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
        ctx.entitiesStream().filter(Entity::isAlive).forEach(entity -> loaded.put(entity.getUUID(), entity));
        threats.entrySet().removeIf(entry -> entry.getValue().expires() < now);
        for (var entry : new ArrayList<>(threats.entrySet())) {
            Entity entity = loaded.get(entry.getKey());
            if (entity != null) {
                Threat old = entry.getValue();
                boolean close = Math.hypot(entity.getX() - ctx.player().getX(), entity.getZ() - ctx.player().getZ()) < old.distance();
                threats.put(entry.getKey(), new Threat(entity.blockPosition().immutable(), old.distance(), close ? now + 15000 : old.expires()));
            }
        }
        int radius = Math.max(4, Math.min(32, Baritone.settings().conflictMobRadius.value));
        for (Entity entity : loaded.values()) {
            if (!(entity instanceof Mob mob) || entity.distanceToSqr(ctx.player()) > radius * radius) continue;
            boolean neutral = isType(entity, "enderman") || isType(entity, "zombified_piglin") || isType(entity, "piglin");
            if ((entity.getType().getCategory() == MobCategory.MONSTER && !neutral) || mob.getTarget() == ctx.player()) remember(entity, false);
        }
        if (!threats.isEmpty()) begin();
    }

    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || !ctx.player().isAlive()) {
            onLostControl();
            return null;
        }
        if (calcFailed && ++failedPaths >= 3) {
            logDirect("No safe retreat path was found; stopping bot work");
            stopTasks();
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (tryDefend(isSafeToCancel)) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
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

    private boolean tryDefend(boolean safe) {
        if (!safe || !"defend".equalsIgnoreCase(Baritone.settings().botConflictMode.value)
                || attackerId == null || System.currentTimeMillis() > defendUntil || baritone.getSculkProcess().isActive()) return false;
        Entity target = ctx.entitiesStream().filter(entity -> attackerId.equals(entity.getUUID())).findFirst().orElse(null);
        if (!(target instanceof LivingEntity) || !target.isAlive() || target.distanceToSqr(ctx.player()) > 8
                || !ctx.player().hasLineOfSight(target)) return false;
        if (target instanceof net.minecraft.world.entity.player.Player && baritone.getBotControl().isTrusted(target.getUUID())) return false;
        int nearby = (int) threats.values().stream().filter(threat -> threat.position().distSqr(ctx.playerFeet()) < 10 * 10).count();
        if (!SurvivalPolicy.mayDefend(ctx.player().getHealth(), ctx.player().getFoodData().getFoodLevel(), nearby,
                isType(target, "warden"), isType(target, "creeper"), true)) return false;
        int weapon = -1;
        for (int i = 0; i < 9; i++) {
            var stack = ctx.player().getInventory().getItem(i);
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= Math.max(1, Baritone.settings().botToolReserve.value)) continue;
            if (stack.is(ItemTags.SWORDS)) { weapon = i; break; }
            if (weapon < 0 && stack.is(ItemTags.AXES)) weapon = i;
        }
        if (weapon < 0 || ctx.minecraft().gameMode == null) return false;
        baritone.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
        if (previousSlot < 0) previousSlot = ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(weapon); ctx.playerController().syncHeldItem();
        baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), target.getEyePosition(), ctx.playerRotations()), true);
        if (attackDelay > 0) attackDelay--;
        if (attackDelay == 0 && ctx.player().getAttackStrengthScale(0) >= 0.95f) {
            releaseShield();
            ctx.player().getInventory().setSelectedSlot(weapon); ctx.playerController().syncHeldItem();
            ctx.minecraft().gameMode.attack(ctx.player(), target);
            ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false); attackDelay = 10;
        } else if (ctx.player().getOffhandItem().is(Items.SHIELD)) {
            ownsShield = true;
            if (!ctx.player().isUsingItem()) ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.OFF_HAND);
        }
        return true;
    }
    private void releaseShield() {
        boolean release = ownsShield; ownsShield = false;
        if (ctx.player() != null) {
            if (release && ctx.player().isUsingItem() && ctx.minecraft().gameMode != null) ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
            if (previousSlot >= 0) { ctx.player().getInventory().setSelectedSlot(previousSlot); ctx.playerController().syncHeldItem(); }
        }
        previousSlot = -1;
    }
    private void stopTasks() {
        baritone.getJobManager().fail("No safe retreat path was found");
        baritone.getSurvivalProcess().onLostControl();
        baritone.getSculkProcess().onLostControl();
        baritone.getMineProcess().cancel();
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
    @Override public void onLostControl() { releaseShield(); active = false; quietTicks = failedPaths = attackDelay = 0; threats.clear(); attackerId = null; defendUntil = 0; }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return "Retreating from conflict"; }
}

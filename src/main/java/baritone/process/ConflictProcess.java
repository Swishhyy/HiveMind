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
import baritone.utils.social.GoalRetreat;
import baritone.utils.social.SocialPolicy;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
    private ClientLevel observedWorld;
    private boolean active;
    private int quietTicks;
    private int failedPaths;

    public ConflictProcess(Baritone baritone) { super(baritone); }
    @Override public boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.1; }

    private boolean hasWork() {
        return baritone.getMineProcess().isActive() || baritone.getDepositProcess().isActive()
                || baritone.getStalkProcess().isActive() || baritone.getFollowProcess().isActive()
                || baritone.getCustomGoalProcess().isActive() || baritone.getBuilderProcess().isActive()
                || baritone.getExploreProcess().isActive() || baritone.getFarmProcess().isActive()
                || baritone.getGetToBlockProcess().isActive();
    }

    public void onDamage(DamageSource source) {
        if (!Baritone.settings().fleeConflicts.value || ctx.player() == null || ctx.world() == null || !hasWork()) return;
        Entity attacker = source.getEntity();
        if (attacker == null || attacker == ctx.player()) return; // Environmental damage is not evidence that a bystander attacked.
        if (observedWorld != ctx.world()) { onLostControl(); observedWorld = ctx.world(); }
        quietTicks = 0;
        remember(attacker);
        begin();
    }

    private void remember(Entity entity) {
        if (threats.size() >= 32 && !threats.containsKey(entity.getUUID())) threats.remove(threats.keySet().iterator().next());
        int distance = SocialPolicy.fleeDistance(Baritone.settings().conflictFleeDistance.value, entity.getType() == EntityType.WARDEN);
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
            boolean neutral = entity.getType() == EntityType.ENDERMAN || entity.getType() == EntityType.ZOMBIFIED_PIGLIN
                    || entity.getType() == EntityType.PIGLIN;
            if ((entity.getType().getCategory() == MobCategory.MONSTER && !neutral) || mob.getTarget() == ctx.player()) remember(entity);
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

    private void stopTasks() {
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
    @Override public void onLostControl() { active = false; quietTicks = failedPaths = 0; threats.clear(); }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return "Retreating from conflict"; }
}

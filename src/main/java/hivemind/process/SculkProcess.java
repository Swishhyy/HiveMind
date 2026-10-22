/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.process;

import hivemind.HiveMind;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.TickEvent;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.input.Input;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.BotWork;
import hivemind.utils.bot.SculkPolicy;
import hivemind.utils.social.GoalRetreat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/** Local hazard discovery, expensive detours, and a quiet exit before mining resumes. */
public final class SculkProcess extends HiveMindProcessHelper implements IBehavior {
    private final Map<BlockPos, SculkPolicy.Zone> known = new LinkedHashMap<>();
    private volatile SculkPolicy.Snapshot snapshot = SculkPolicy.Snapshot.EMPTY;
    private Level observed;
    private long lastScan;
    private long stalkRetryAt;
    private BlockPos scanOrigin;
    private boolean active, blocked;
    private int quietTicks, failures;
    private List<GoalRetreat.Threat> exitZones = List.of();
    public SculkProcess(HiveMind bot) { super(bot); }
    public SculkPolicy.Snapshot snapshot() { return observed == ctx.world() ? snapshot : SculkPolicy.Snapshot.EMPTY; }
    @Override public boolean isActive() { return active; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return -0.15; }
    public void retry() { blocked = false; failures = 0; }
    public boolean quiet() {
        return active && (hivemind.getPathingControlManager().mostRecentInControl().orElse(null) == this
                || hivemind.getPathingControlManager().mostRecentInControl().orElse(null) == hivemind.getConflictProcess());
    }
    public void refresh() {
        if (ctx.world() == null || ctx.player() == null) return;
        if (observed != ctx.world()) {
            known.clear(); snapshot = SculkPolicy.Snapshot.EMPTY; lastScan = 0; scanOrigin = null; onLostControl(); observed = ctx.world();
        }
        if (!HiveMind.settings().mineAvoidSculk.value) return;
        long now = System.currentTimeMillis();
        if (now - lastScan < 500 && scanOrigin != null && scanOrigin.distSqr(ctx.playerFeet()) < 16) return;
        lastScan = now; scanOrigin = ctx.playerFeet().immutable();
        boolean discovered = false, alarm = false;
        int radius = Math.max(4, Math.min(24, HiveMind.settings().sculkAvoidRadius.value));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
            int x = scanOrigin.getX() + dx, z = scanOrigin.getZ() + dz;
            cursor.set(x, scanOrigin.getY(), z); if (!ctx.world().hasChunkAt(cursor)) continue;
            for (int dy = -16; dy <= 16; dy++) {
                cursor.set(x, scanOrigin.getY() + dy, z);
                BlockState state = ctx.world().getBlockState(cursor);
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                boolean trigger = id.equals("sculk_shrieker") || id.equals("sculk_sensor") || id.equals("calibrated_sculk_sensor");
                if (trigger) {
                    BlockPos key = cursor.immutable();
                    if (!known.containsKey(key)) discovered = true;
                    known.put(key, new SculkPolicy.Zone(x, cursor.getY(), z, id.equals("calibrated_sculk_sensor") ? Math.max(20, radius) : radius));
                    if (cursor.distSqr(scanOrigin) <= 24 * 24 && state.hasProperty(BlockStateProperties.SHRIEKING) && state.getValue(BlockStateProperties.SHRIEKING)) alarm = true;
                } else known.remove(cursor);
            }
        }
        while (known.size() > 2048) known.remove(known.keySet().iterator().next());
        snapshot = new SculkPolicy.Snapshot(new ArrayList<>(known.values()));
        if (discovered && BotWork.active(hivemind)) hivemind.getPathingBehavior().softCancelIfSafe();
        List<GoalRetreat.Threat> nearby = snapshot.zones().stream()
                .filter(zone -> zone.distanceSquared(scanOrigin.getX(), scanOrigin.getY(), scanOrigin.getZ()) <= Math.pow(zone.radius() + 2, 2))
                .map(zone -> new GoalRetreat.Threat(new BlockPos(zone.x(), zone.y(), zone.z()), zone.radius() + 4)).toList();
        if ((alarm || !nearby.isEmpty()) && BotWork.active(hivemind)) {
            if (nearby.isEmpty()) nearby = snapshot.zones().stream().filter(zone -> zone.distanceSquared(scanOrigin.getX(), scanOrigin.getY(), scanOrigin.getZ()) < 24 * 24)
                    .map(zone -> new GoalRetreat.Threat(new BlockPos(zone.x(), zone.y(), zone.z()), zone.radius() + 4)).toList();
            exitZones = nearby;
            if (!active) {
                active = true; quietTicks = failures = 0;
                hivemind.getDepositProcess().suspendForConflict();
                hivemind.getWorkstationProcess().suspendForConflict();
                if (ctx.player().containerMenu != ctx.player().inventoryMenu && ctx.player().containerMenu.getCarried().isEmpty()) ctx.player().closeContainer();
                hivemind.getInputOverrideHandler().clearAllKeys(); ctx.playerController().resetBlockRemoving();
                hivemind.getPathingBehavior().softCancelIfSafe();
                logDirect("Sculk detected nearby; leaving quietly before resuming");
            }
        }
    }
    @Override public PathingCommand onTick(boolean failed, boolean safe) {
        if (!HiveMind.settings().mineAvoidSculk.value || ctx.world() == null || ctx.player() == null) { onLostControl(); return null; }
        if (System.currentTimeMillis() < stalkRetryAt) {
            if (safe) hivemind.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (failed && ++failures >= 3) {
            if (hivemind.getStalkProcess().isActive() && !hivemind.getStalkProcess().hunting()) {
                failures = 0; stalkRetryAt = System.currentTimeMillis() + 5000;
                logDirect("Quiet escape route blocked; keeping the stalk target and retrying");
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            blocked = true; hivemind.getJobManager().fail("No quiet path out of the sculk area");
            logDirect("No quiet escape path; bot work paused. Use stop before taking manual control.");
        }
        if (blocked) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        GoalRetreat goal = exitZones.isEmpty() ? null : new GoalRetreat(exitZones);
        if (goal == null || goal.isInGoal(ctx.playerFeet())) {
            if (++quietTicks >= 60) { onLostControl(); return null; }
            if (safe) hivemind.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        quietTicks = 0;
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.player() == null || ctx.world() == null) return;
        if (BotWork.active(hivemind) || active) refresh();
        if (quiet()) {
            hivemind.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            hivemind.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
        }
    }
    @Override public void onLostControl() { active = blocked = false; quietTicks = failures = 0; stalkRetryAt = 0; exitZones = List.of(); }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return blocked ? "Sculk escape paused" : "Leaving sculk quietly"; }
}

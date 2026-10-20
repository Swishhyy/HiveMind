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
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.social.GoalStandoff;
import baritone.utils.social.SocialPolicy;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Watches one UUID using only positions the server has actually sent to this client. */
public final class StalkProcess extends BaritoneProcessHelper implements IBehavior {
    private record Seen(BlockPos position, long time) {}
    private final Map<UUID, Seen> seen = new HashMap<>();
    private final Set<BlockPos> failedVantages = new HashSet<>();
    private ClientLevel observedWorld;
    private UUID targetId;
    private String targetName;
    private BlockPos searchAnchor;
    private BlockPos vantageAnchor;
    private BlockPos vantage;
    private Goal searchGoal;
    private long searchStarted;
    private int searchStep;
    private int legTicks;
    private boolean checkedLastSeen;
    private boolean watching;
    private Player watchedPlayer;
    private int vantageRetry;

    public StalkProcess(Baritone baritone) { super(baritone); }
    @Override public boolean isActive() { return targetId != null; }

    public void start(String requested) {
        if (ctx.minecraft().getConnection() == null) throw new IllegalStateException("Join a world before stalking a player");
        rememberPlayers();
        List<PlayerInfo> online = ctx.minecraft().getConnection().getOnlinePlayers().stream()
                .filter(info -> !info.getProfile().id().equals(ctx.player().getUUID())).toList();
        boolean random = requested == null || requested.equalsIgnoreCase("random");
        PlayerInfo selected;
        if (random) {
            List<PlayerInfo> nearby = online.stream().filter(info -> loaded(info.getProfile().id()) != null).toList();
            List<PlayerInfo> pool = nearby.isEmpty() ? online : nearby;
            if (pool.isEmpty()) throw new IllegalStateException("No other players are online");
            selected = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        } else {
            selected = online.stream().filter(info -> info.getProfile().name().equalsIgnoreCase(requested)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("That player is not in this server's player list"));
        }
        baritone.getPathingBehavior().cancelEverything();
        targetId = selected.getProfile().id();
        targetName = selected.getProfile().name();
        searchAnchor = ctx.playerFeet().immutable();
        searchStarted = System.currentTimeMillis();
        logDirect("Stalking " + targetName + " from about " + distance() + " blocks; crouching while watching");
        if (loaded(targetId) == null) logDirect("Target is outside client view; checking last-seen positions and searching locally");
    }

    public String status() { return isActive() ? displayName0() : "No stalk target"; }
    private int distance() { return SocialPolicy.stalkDistance(Baritone.settings().stalkDistance.value); }
    private Player loaded(UUID id) {
        return ctx.world().players().stream().filter(player -> player.getUUID().equals(id) && player.isAlive() && !player.isSpectator())
                .findFirst().orElse(null);
    }
    private void rememberPlayers() {
        if (ctx.world() != observedWorld) {
            seen.clear();
            observedWorld = ctx.world();
        }
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(entry -> now - entry.getValue().time() > 120000);
        for (Player player : ctx.world().players()) if (player != ctx.player() && player.isAlive() && !player.isSpectator()) {
            seen.put(player.getUUID(), new Seen(player.blockPosition().immutable(), now));
        }
    }

    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        watching = false;
        watchedPlayer = null;
        if (ctx.world() != observedWorld || ctx.minecraft().getConnection() == null
                || ctx.minecraft().getConnection().getPlayerInfo(targetId) == null) return stop("Target left this world or server");
        Player target = loaded(targetId);
        if (target == null) return search(calcFailed);
        searchAnchor = target.blockPosition().immutable();
        searchStarted = System.currentTimeMillis();
        searchGoal = null;
        legTicks = searchStep = 0;
        checkedLastSeen = false;
        int distance = distance();
        GoalStandoff ring = new GoalStandoff(target.blockPosition(), distance);
        if (vantageAnchor == null || vantageAnchor.distSqr(target.blockPosition()) > 16) {
            vantageAnchor = target.blockPosition().immutable();
            vantage = null;
            failedVantages.clear();
            vantageRetry = 0;
        }
        boolean failedVantage = calcFailed && vantage != null;
        if (failedVantage) { failedVantages.add(vantage); vantage = null; vantageRetry = 0; }
        if (ring.isInGoal(ctx.playerFeet()) && ctx.player().hasLineOfSight(target)) {
            watching = isSafeToCancel;
            watchedPlayer = target;
            if (watching) {
                baritone.getInputOverrideHandler().clearAllKeys();
                ctx.playerController().resetBlockRemoving();
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (ring.isInGoal(ctx.playerFeet()) && !ctx.player().hasLineOfSight(target)) {
            if (vantageRetry-- <= 0) { vantage = findVantage(target); vantageRetry = 40; }
            if (vantage != null) return new PathingCommand(new GoalNear(vantage, 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            // Terrain can block every visible vantage. Hold the distance and retry rather than walk into the player.
            watching = isSafeToCancel;
            watchedPlayer = target;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (calcFailed && !failedVantage) return stop("No safe path to the target's viewing distance");
        return new PathingCommand(ring, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand search(boolean calcFailed) {
        long timeout = Math.max(10000L, Math.min(600000L, Baritone.settings().stalkSearchTimeoutMS.value));
        if (System.currentTimeMillis() - searchStarted > timeout) return stop("Target was not found within the local search limit");
        if (searchGoal != null && (calcFailed || searchGoal.isInGoal(ctx.playerFeet()) || ++legTicks > 600)) {
            searchGoal = null;
            legTicks = 0;
        }
        if (searchGoal == null) {
            Seen last = seen.get(targetId);
            if (!checkedLastSeen && last != null) {
                checkedLastSeen = true;
                searchGoal = new GoalStandoff(last.position(), distance());
            } else {
                int radius = SocialPolicy.searchRadius(Baritone.settings().stalkSearchRadius.value);
                if (searchStep >= SocialPolicy.searchSteps(radius)) return stop("Target was not found in the local search area");
                SocialPolicy.Offset offset = SocialPolicy.searchOffset(searchStep++, radius);
                searchGoal = new GoalXZ(searchAnchor.getX() + offset.x(), searchAnchor.getZ() + offset.z());
            }
        }
        return new PathingCommand(searchGoal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private BlockPos findVantage(Player target) {
        CalculationContext context = new CalculationContext(baritone);
        BlockPos center = target.blockPosition();
        double initial = Math.atan2(ctx.player().getZ() - target.getZ(), ctx.player().getX() - target.getX());
        List<BlockPos> candidates = new ArrayList<>();
        for (int step = 0; step < 8; step++) {
            double angle = initial + step * Math.PI / 4;
            int x = (int) Math.floor(target.getX() + Math.cos(angle) * distance());
            int z = (int) Math.floor(target.getZ() + Math.sin(angle) * distance());
            for (int y = center.getY() - 6; y <= center.getY() + 6; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (failedVantages.contains(pos) || !ctx.world().hasChunkAt(pos)
                        || !MovementHelper.canWalkThrough(context, x, y, z) || !MovementHelper.canWalkThrough(context, x, y + 1, z)
                        || !MovementHelper.canWalkOn(context, x, y - 1, z)
                        || !context.get(pos).getFluidState().isEmpty() || !context.get(pos.above()).getFluidState().isEmpty()) continue;
                Vec3 eye = Vec3.atCenterOf(pos).add(0, 1.1, 0);
                if (ctx.world().clip(new ClipContext(eye, target.getEyePosition(), ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS) candidates.add(pos);
            }
        }
        return candidates.stream().min(java.util.Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
    }

    @Override public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null) {
            onLostControl();
            seen.clear();
            observedWorld = null;
            return;
        }
        if (observedWorld != null && observedWorld != ctx.world()) onLostControl();
        rememberPlayers();
        if (watching && watchedPlayer != null && !baritone.getConflictProcess().isActive()
                && baritone.getPathingControlManager().mostRecentInControl().orElse(null) == this) {
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                    watchedPlayer.getEyePosition(), ctx.playerRotations()), true);
        }
    }
    private PathingCommand stop(String reason) {
        logDirect("Stalking stopped: " + reason);
        onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    @Override public void onLostControl() {
        targetId = null;
        targetName = null;
        searchAnchor = vantageAnchor = vantage = null;
        searchGoal = null;
        watching = checkedLastSeen = false;
        watchedPlayer = null;
        searchStep = legTicks = vantageRetry = 0;
        failedVantages.clear();
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, false);
    }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return (watching ? "Watching " : "Stalking/searching for ") + targetName + " at about " + distance() + " blocks"; }
}

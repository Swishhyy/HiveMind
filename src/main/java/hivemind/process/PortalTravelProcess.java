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
import hivemind.api.event.events.WorldEvent;
import hivemind.api.pathing.goals.GoalBlock;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.pathing.movement.CalculationContext;
import hivemind.pathing.movement.MovementHelper;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.PortalLinks;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Portal navigation overlay. Only an expected, connected portal transition preserves mining work. */
public final class PortalTravelProcess extends HiveMindProcessHelper implements IBehavior {
    private enum Stage { IDLE, APPROACH, WAIT, EXIT, LEAVE_BEFORE_TRIP, COOLDOWN }
    private Stage stage = Stage.IDLE;
    private String source;
    private String destination;
    private Path worldRoot;
    private BlockPos entrance;
    private BlockPos exit;
    private long transitionDeadline;
    private long cooldownUntil;
    private int stageTicks;
    private int arrivalTicks;
    private Connection tripConnection;

    public PortalTravelProcess(HiveMind hivemind) { super(hivemind); }

    public void start(String targetDimension) {
        start(targetDimension, null);
    }

    public void start(String targetDimension, BlockPos targetPosition) {
        if (isActive()) throw new IllegalStateException("A portal trip is already running");
        String current = MiningRouteAccess.dimension(ctx);
        if (current.equals(targetDimension)) return;
        if (!WorldRoutes.connects(current, targetDimension)) {
            throw new IllegalStateException("Automatic portal travel supports the Overworld and Nether only");
        }
        BlockPos known = knownEntrance(targetDimension, targetPosition);
        if (known == null) {
            throw new IllegalStateException("No known Nether portal here. Look at an active portal and run portal set");
        }
        worldRoot = MiningRouteAccess.root(ctx);
        source = current;
        destination = targetDimension;
        entrance = known;
        tripConnection = ctx.player().connection.getConnection();
        arrivalTicks = 0;
        if (insidePortal()) {
            exit = safeExit();
            if (exit == null) throw new IllegalStateException("No safe place to leave the portal before traveling");
            change(Stage.LEAVE_BEFORE_TRIP);
        } else {
            change(System.currentTimeMillis() < cooldownUntil ? Stage.COOLDOWN : Stage.APPROACH);
        }
        logDirect("Traveling through a Nether portal to " + destination);
    }

    public BlockPos knownEntrance() {
        return knownEntrance(null, null);
    }

    private BlockPos knownEntrance(String targetDimension, BlockPos targetPosition) {
        if (targetDimension != null && targetPosition != null) {
            WorldRoutes.Endpoint goal = new WorldRoutes.Endpoint(targetDimension, targetPosition.getX(), targetPosition.getY(), targetPosition.getZ());
            var here=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());long now=System.currentTimeMillis();
            WorldRoutes.Endpoint best = new PortalLinks(MiningRouteAccess.root(ctx)).recent().stream().filter(link->link.from().dimension().equals(here.dimension())&&link.to().dimension().equals(targetDimension))
                    .min(java.util.Comparator.comparingDouble(link->Math.hypot((double)here.x()-link.from().x(),(double)here.z()-link.from().z())+Math.hypot((double)goal.x()-link.to().x(),(double)goal.z()-link.to().z())+hivemind.getReliability().routes().penalty("portal",link.from(),now))).map(PortalLinks.Link::from).orElse(null);
            if (best != null) return MiningRouteAccess.position(best);
        }
        BlockPos nearby = nearbyPortal(6);
        if (nearby != null) return bottom(nearby);
        WorldRoutes.Endpoint saved = MiningRouteAccess.store(ctx).portal(MiningRouteAccess.dimension(ctx));
        if (saved != null) return MiningRouteAccess.position(saved);
        return ctx.worldData().getCachedWorld().getLocationsOf("nether_portal", 16,
                        ctx.playerFeet().getX(), ctx.playerFeet().getZ(), 2).stream()
                .min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).map(this::bottom).orElse(null);
    }

    private boolean connected() {
        // Keep the connection itself: Minecraft may temporarily have no LocalPlayer during a transfer.
        return tripConnection != null && tripConnection.isConnected();
    }

    public boolean canPreserveTransition(WorldEvent event) {
        if (stage == Stage.APPROACH && ctx.player() != null && ctx.world() != null && insidePortal()
                && ctx.playerFeet().distSqr(entrance) < 16) {
            // Creative mode can transfer before the next process tick observes portal contact.
            transitionDeadline = System.currentTimeMillis() + portalTimeout();
            change(Stage.WAIT);
        }
        String incoming = event == null || event.getWorld() == null ? null : event.getWorld().dimension().identifier().toString();
        return WorldRoutes.preserveTransition(stage == Stage.WAIT, connected(), System.currentTimeMillis(),
                transitionDeadline, destination, incoming);
    }

    @Override public boolean isActive() { return stage != Stage.IDLE; }
    @Override public boolean isTemporary() { return true; }
    @Override public double priority() { return DEFAULT_PRIORITY + 0.75; } // Above deposit/mining, below user pause.

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return travelTick(calcFailed, isSafeToCancel);
        } catch (IllegalStateException e) {
            return fail(e.getMessage());
        }
    }

    private PathingCommand travelTick(boolean calcFailed, boolean safe) {
        if (ctx.player() == null || ctx.world() == null || !connected()) return fail("disconnected");
        if (ctx.player().connection.getConnection() != tripConnection) return fail("the connection changed");
        if (!worldRoot.equals(MiningRouteAccess.root(ctx))) return fail("the server or save changed");
        if (calcFailed) return fail("no usable route to or out of the portal");
        if (++stageTicks > 12000) return fail("portal route timed out");
        String current = MiningRouteAccess.dimension(ctx);
        if (stage == Stage.WAIT) {
            if (System.currentTimeMillis() >= transitionDeadline) return fail("the portal did not change dimensions in time");
            if (destination.equals(current)) {
                // Arrival coordinates come from the actual server transfer, never from 8:1 coordinate guesses.
                BlockPos arrived = nearbyPortal(6);
                if (arrived == null) return ++arrivalTicks > 200 ? fail("the arrival portal could not be identified") : pause();
                WorldRoutes.Endpoint actual = MiningRouteAccess.endpoint(ctx, bottom(arrived));
                MiningRouteAccess.store(ctx).setPortal(actual);
                new PortalLinks(worldRoot).record(new WorldRoutes.Endpoint(source, entrance.getX(), entrance.getY(), entrance.getZ()), actual);
                hivemind.getReliability().routes().success("portal",new WorldRoutes.Endpoint(source,entrance.getX(),entrance.getY(),entrance.getZ()),System.currentTimeMillis());
                hivemind.getReliability().success("portal","Expected dimension and portal exit confirmed: "+actual);
                hivemind.getOperations().journal().note("Portal", source + " " + entrance + " arrived " + destination + " " + bottom(arrived));
                hivemind.getMineProcess().invalidateWorldTargets();
                exit = safeExit();
                if (exit == null) return fail("no safe exit beside the arrival portal");
                change(Stage.EXIT);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (!source.equals(current)) return fail("arrived in an unexpected dimension");
            hivemind.getInputOverrideHandler().clearAllKeys();
            return pause();
        }
        if (!current.equals(stage == Stage.EXIT ? destination : source)) return fail("unexpected dimension change");
        if (stage == Stage.EXIT || stage == Stage.LEAVE_BEFORE_TRIP) {
            if (new GoalBlock(exit).isInGoal(ctx.playerFeet()) && !insidePortal() && safe) {
                cooldownUntil = System.currentTimeMillis() + 15000L;
                if (stage == Stage.LEAVE_BEFORE_TRIP) {
                    change(Stage.COOLDOWN);
                } else {
                    logDirect("Arrived in " + destination + "; continuing the mining trip");
                    onLostControl();
                }
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return path(exit);
        }
        if (stage == Stage.COOLDOWN) {
            if (insidePortal()) {
                exit = safeExit();
                if (exit == null) return fail("no safe portal exit during cooldown");
                change(Stage.LEAVE_BEFORE_TRIP);
                return path(exit);
            }
            if (System.currentTimeMillis() < cooldownUntil) return pause();
            change(Stage.APPROACH);
        }
        if (ctx.world().hasChunkAt(entrance)) {
            if (!ctx.world().getBlockState(entrance).is(Blocks.NETHER_PORTAL)) {
                return fail("the saved portal was removed or extinguished; save an active portal with portal set");
            }
            entrance = bottom(entrance);
        }
        if (insidePortal() && ctx.playerFeet().distSqr(entrance) < 16 && safe) {
            MiningRouteAccess.store(ctx).setPortal(MiningRouteAccess.endpoint(ctx, entrance));
            transitionDeadline = System.currentTimeMillis() + portalTimeout();
            change(Stage.WAIT);
            hivemind.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return path(entrance);
    }

    private PathingCommand path(BlockPos pos) {
        return new PathingCommand(new GoalBlock(pos), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand pause() { return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE); }
    private void change(Stage next) { stage = next; stageTicks = 0; }
    private long portalTimeout() {
        return Math.max(10000L, Math.min(300000L, HiveMind.settings().miningPortalTimeoutMS.value));
    }

    private boolean insidePortal() {
        return ctx.world().getBlockState(ctx.playerFeet()).is(Blocks.NETHER_PORTAL)
                || ctx.world().getBlockState(ctx.playerFeet().above()).is(Blocks.NETHER_PORTAL);
    }

    private BlockPos bottom(BlockPos pos) {
        if (!ctx.world().hasChunkAt(pos)) return pos.immutable();
        BlockPos result = pos.immutable();
        for (int i = 0; i < 21 && ctx.world().getBlockState(result.below()).is(Blocks.NETHER_PORTAL); i++) result = result.below();
        return result;
    }

    private BlockPos nearbyPortal(int radius) {
        BlockPos feet = ctx.playerFeet();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            for (int y = -2; y <= 3; y++) {
                BlockPos pos = feet.offset(x, y, z);
                if (ctx.world().hasChunkAt(pos) && ctx.world().getBlockState(pos).is(Blocks.NETHER_PORTAL)) candidates.add(pos);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    private BlockPos safeExit() {
        CalculationContext context = new CalculationContext(hivemind);
        BlockPos feet = ctx.playerFeet();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -2; y <= 2; y++) {
            BlockPos p = feet.offset(x, y, z);
            if (!ctx.world().hasChunkAt(p) || p.distSqr(feet) < 4 || p.distSqr(feet) > 36
                    || context.get(p).is(Blocks.NETHER_PORTAL) || context.get(p.above()).is(Blocks.NETHER_PORTAL)
                    || !context.get(p).getFluidState().isEmpty() || !context.get(p.above()).getFluidState().isEmpty()) continue;
            if (MovementHelper.canWalkThrough(context, p.getX(), p.getY(), p.getZ())
                    && MovementHelper.canWalkThrough(context, p.getX(), p.getY() + 1, p.getZ())
                    && MovementHelper.canWalkOn(context, p.getX(), p.getY() - 1, p.getZ())) candidates.add(p);
        }
        return candidates.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    private PathingCommand fail(String reason) {
        if(entrance!=null&&source!=null&&ctx.player()!=null&&ctx.world()!=null)hivemind.getReliability().routes().failed("portal",new WorldRoutes.Endpoint(source,entrance.getX(),entrance.getY(),entrance.getZ()),reason,System.currentTimeMillis());
        if (ctx.player() != null && ctx.world() != null) hivemind.getOperations().journal().note("Portal", reason);
        hivemind.getCompanionProcess().stopWithReason("Portal: " + reason);
        hivemind.getRepairProcess().stopWithReason("Portal: " + reason);
        hivemind.getWorkstationProcess().onLostControl();
        hivemind.getJobManager().fail("Portal: " + reason);
        if (hivemind.getDepositProcess().isResupplying()) hivemind.getSurvivalProcess().supplyFailed("Portal: " + reason);
        hivemind.getMineProcess().cancel();
        hivemind.getDepositProcess().onLostControl();
        onLostControl();
        logDirect("Portal trip stopped: " + reason + ". Mining stopped.");
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override public void onPlayerDeath() {
        if (isActive() || hivemind.getMineProcess().isActive() || hivemind.getDepositProcess().isActive()) {
            hivemind.getPathingBehavior().cancelEverything();
        }
    }

    @Override public void onLostControl() {
        stage = Stage.IDLE;
        source = destination = null;
        entrance = exit = null;
        worldRoot = null;
        transitionDeadline = 0;
        tripConnection = null;
    }

    @Override public String displayName0() { return "Portal trip: " + stage + " to " + destination; }
}

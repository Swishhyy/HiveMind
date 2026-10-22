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
import hivemind.api.IHiveMind;
import hivemind.api.event.events.*;
import hivemind.api.event.events.type.EventState;
import hivemind.api.event.listener.AbstractGameEventListener;
import hivemind.api.pathing.goals.Goal;
import hivemind.api.pathing.goals.GoalBlock;
import hivemind.api.pathing.goals.GoalNear;
import hivemind.api.pathing.goals.GoalXZ;
import hivemind.api.pathing.goals.GoalYLevel;
import hivemind.api.pathing.movement.IMovement;
import hivemind.api.pathing.path.IPathExecutor;
import hivemind.api.process.IHiveMindProcess;
import hivemind.api.process.IElytraProcess;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.BetterBlockPos;
import hivemind.api.utils.Rotation;
import hivemind.api.utils.RotationUtils;
import hivemind.api.utils.input.Input;
import hivemind.pathing.movement.CalculationContext;
import hivemind.pathing.movement.movements.MovementFall;
import hivemind.process.elytra.*;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.PathingCommandContext;
import hivemind.utils.bot.FlightPolicy;
import hivemind.utils.bot.TakeoffState;
import hivemind.utils.bot.LandingRetry;
import hivemind.utils.bot.FlightPolicy.Point;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static hivemind.api.pathing.movement.ActionCosts.COST_INF;

public class ElytraProcess extends HiveMindProcessHelper implements IHiveMindProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private boolean goingToLandingSpot;
    private BetterBlockPos landingSpot;
    private boolean reachedGoal; // this basically just prevents potential notification spam
    private Goal goal;
    private ElytraBehavior behavior;
    private NetherPathfinderContext npfContext;
    private boolean predictingTerrain;
    private boolean allowTight;
    private boolean allowAboveBuildLimit;
    private boolean allowAboveRoof;
    private IHiveMindProcess travelOwner;
    private boolean autonomousTravel;
    private TakeoffState takeoffState;
    private boolean requestedLanding;
    private boolean emergencyTravel;
    private BlockPos takeoffPoint;
    private boolean deferPathInitialization;
    @Override public boolean isTemporary(){return autonomousTravel;}
    public boolean ownedBy(IHiveMindProcess owner){return travelOwner==owner&&isActive();}
    public void travelFor(IHiveMindProcess owner,Goal destination,BlockPos takeoff,boolean emergency){
        deferPathInitialization=true;
        try{pathTo(destination);}finally{deferPathInitialization=false;}
        travelOwner=owner;autonomousTravel=true;emergencyTravel=emergency;takeoffPoint=takeoff;
        takeoffState=new TakeoffState(takeoff==null?null:new Point(takeoff.getX()+.5,takeoff.getY(),takeoff.getZ()+.5));
        // Vanilla auto-jump waits for a walking fall before computing this path. Autonomous
        // ground takeoff needs its flight path NOW, otherwise the solver never uses a rocket.
        behavior.pathManager.pathToDestination(takeoff==null?ctx.playerFeet():takeoff);
    }
    public boolean retargetFor(IHiveMindProcess owner,BlockPos destination,boolean emergency){
        if(!isActive()||!isSupportedPos(destination)||requestedLanding||goingToLandingSpot||state==State.LANDING)return false;
        boolean previous=emergencyTravel;emergencyTravel=emergency;
        if(shouldLandForSafety()||!behavior.retarget(destination)){emergencyTravel=previous;return false;}
        travelOwner=owner;autonomousTravel=true;requestedLanding=false;goingToLandingSpot=false;landingSpot=null;landingSearchState=null;
        if(ctx.player().isFallFlying())state=State.FLYING;
        return true;
    }
    public void requestLanding(){requestedLanding=true;}
    public boolean takeoffCompleted(){return takeoffState!=null&&takeoffState.completed();}
    public boolean needsLaunchBoost(){return travelOwner!=null&&takeoffState!=null&&takeoffState.launching()&&!requestedLanding&&!goingToLandingSpot;}
    public boolean takeoffFailed(){return takeoffState!=null&&takeoffState.failed();}
    private final Semaphore npfSema = new Semaphore(1);

    private static final int SHORT_LANDING_COLUMN_HEIGHT = 15;
    private static final int LONG_LANDING_COLUMN_HEIGHT = 39;
    private static final long LANDING_SEARCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(4); // keep terrain scanning below a small fraction of a tick
    private int landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
    private Set<BetterBlockPos> badLandingSpots = new HashSet<>();
    private LandingSearchState landingSearchState;
    private final LandingRetry landingRetry=new LandingRetry();

    @Override
    public void onLostControl() {
        onLostControl(true);
    }

    public void onLostControl(boolean destroyNpf) {
        travelOwner=null;requestedLanding=emergencyTravel=false;takeoffPoint=null;
        hivemind.getInputOverrideHandler().clearAllKeys();
        this.state = State.START_FLYING; // TODO: null state?
        this.goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.landingRetry.reset();
        this.reachedGoal = false;
        this.goal = null;
        destroyBehaviorAsync();
        if (destroyNpf) {
            destroyNpfContextAsync();
        }
    }

    private ElytraProcess(HiveMind hivemind) {
        super(hivemind);
        hivemind.getGameEventHandler().registerEventListener(this);
    }

    public static IElytraProcess create(final HiveMind hivemind) {
        return NetherPathfinderContext.isSupported()
                ? new ElytraProcess(hivemind)
                : new NullElytraProcess(hivemind);
    }

    @Override
    public boolean isActive() {
        return this.behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        IHiveMindProcess owner=travelOwner;boolean emergency=emergencyTravel;BlockPos takeoff=takeoffPoint;TakeoffState progress=takeoffState;
        this.onLostControl();this.travelOwner=owner;this.emergencyTravel=emergency;this.takeoffPoint=takeoff;this.takeoffState=progress;
        if (destination != null) {
            this.pathTo0(destination, false);
            if(travelOwner!=null)behavior.pathManager.pathToDestination(takeoffPoint==null?ctx.playerFeet():takeoffPoint);
            this.repackChunks();
        }
    }

    private static final String AUTO_JUMP_FAILURE_MSG = "Failed to compute a walking path to a spot to jump off from. Consider starting from a higher location, near an overhang. Or, you can disable elytraAutoJump and just manually begin gliding.";

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if(ctx.player()!=null&&LandingRetry.finished(ctx.player().onGround(),requestedLanding,goingToLandingSpot,state==State.LANDING)){
            onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if(travelOwner!=null&&!travelOwner.isActive()){
            if(ctx.player()!=null&&ctx.player().isFallFlying())requestedLanding=true;
            else{onLostControl();return null;}
        }
        try {
            final long seedSetting = HiveMind.settings().elytraNetherSeed.value;
            if (seedSetting != this.behavior.npfContext.getSeed()) {
                logDirect("Nether seed changed, recalculating path");
                this.resetState();
            }
            if (predictingTerrain != HiveMind.settings().elytraPredictTerrain.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraPredictTerrain setting changed, recalculating path from scratch");
                predictingTerrain = HiveMind.settings().elytraPredictTerrain.value;
                this.resetState();
            }
            if (allowTight != HiveMind.settings().elytraAllowTightSpaces.value) {
                logDirect("elytraAllowTightSpaces setting changed, recalculating path from scratch");
                allowTight = HiveMind.settings().elytraAllowTightSpaces.value;
                this.resetState();
            }
            if (allowAboveBuildLimit != HiveMind.settings().elytraAllowAboveBuildLimit.value) {
                logDirect("elytraAllowAboveBuildLimit setting changed, recalculating path from scratch");
                allowAboveBuildLimit = HiveMind.settings().elytraAllowAboveBuildLimit.value;
                this.resetState();
            }
            if (allowAboveRoof != HiveMind.settings().elytraAllowAboveRoof.value && ctx.player().level().dimension() == Level.NETHER) {
                logDirect("elytraAllowAboveRoof setting changed, recalculating path from scratch");
                allowAboveRoof = HiveMind.settings().elytraAllowAboveRoof.value;
                this.resetState();
            }
        } catch (IllegalArgumentException e) {
            logDirect(e.getMessage(), ChatFormatting.RED);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        this.behavior.onTick();

        if (calcFailed) {
            onLostControl();
            logDirect(AUTO_JUMP_FAILURE_MSG);
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if(travelOwner!=null&&takeoffState!=null&&this.state!=State.LANDING&&!goingToLandingSpot){
            if(!ctx.player().isFallFlying()&&(requestedLanding||shouldLandForSafety())){
                onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            var position=ctx.player().position();
            TakeoffState.Action action=takeoffState.advance(new Point(position.x,position.y,position.z),ctx.player().onGround(),ctx.player().isFallFlying(),
                ctx.player().horizontalCollision||ctx.player().verticalCollision&&!ctx.player().onGround(),!behavior.pathManager.getPath().isEmpty(),ctx.player().getDeltaMovement().y);
            if(action==TakeoffState.Action.RECOVER){
                if(!ctx.player().isFallFlying()||ctx.player().onGround()){
                    onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                if(groundBelowLaunch()){
                    // Stop boosting into the obstruction. Normal glide physics settle onto known nearby ground.
                    behavior.landingMode=true;hivemind.getInputOverrideHandler().clearAllKeys();
                    hivemind.getLookBehavior().updateTarget(new Rotation(ctx.player().getYRot(),65),false);
                    return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                // A cliff/drop needs the normal safe landing search rather than a blind downward turn.
                requestedLanding=true;
            }else if(action==TakeoffState.Action.FINISHED){
                // Ground contact after a real flight starts a new departure at the current location.
                onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
            }else if(action==TakeoffState.Action.APPROACH){
                return new PathingCommand(new GoalNear(takeoffPoint,0),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }else if(!ctx.player().isFallFlying()){
                hivemind.getPathingBehavior().secretInternalSegmentCancel();
                hivemind.getInputOverrideHandler().clearAllKeys();
                if(action!=TakeoffState.Action.WAIT&&currentDestination()!=null){
                    Rotation aim=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),Vec3.atCenterOf(currentDestination()),ctx.playerRotations());
                    hivemind.getLookBehavior().updateTarget(new Rotation(aim.getYaw(),-25),false);
                }
                hivemind.getInputOverrideHandler().setInputForceState(Input.JUMP,action==TakeoffState.Action.JUMP);
                return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }

        boolean safetyLanding = requestedLanding;
        if (ctx.player().isFallFlying() && shouldLandForSafety()) {
            if (HiveMind.settings().elytraAllowEmergencyLand.value) {
                if(landingRetry.announce(System.currentTimeMillis()))logDirect("Emergency landing - almost out of elytra durability or fireworks");
                safetyLanding = true;
            } else {
                if(landingRetry.announce(System.currentTimeMillis()))logDirect("almost out of elytra durability or fireworks, but I'm going to continue since elytraAllowEmergencyLand is false");
            }
        }
        if (ctx.player().isFallFlying() && this.state != State.LANDING && (this.behavior.pathManager.isComplete() || safetyLanding)) {
            final BetterBlockPos last = this.behavior.pathManager.path.getLast();
            if (last != null && (last.distToCenterSqr(ctx.player().position()) < (48 * 48) || safetyLanding) && (!goingToLandingSpot || (safetyLanding && this.landingSpot == null))) {
                if (this.landingRetry.announce(System.currentTimeMillis())) {
                    logDirect("Path complete, searching for safe landing spot...");
                }
                BetterBlockPos landingSpot = findSafeLandingSpot(ctx.playerFeet());
                // Incremental searches retain their frontier; exhausted passes retry after a short cooldown.
                if (landingSpot != null) {
                    logDirect("Found potential landing spot.");
                    this.pathTo0(landingSpot, true);
                    this.landingSpot = landingSpot;
                    this.goingToLandingSpot = true;
                } else {
                    this.goingToLandingSpot = false;
                }
            }

            if (last != null && last.distToCenterSqr(ctx.player().position()) < 1) {
                if (HiveMind.settings().notificationOnPathComplete.value && !reachedGoal) {
                    logNotification("Pathing complete", false);
                }
                if (HiveMind.settings().disconnectOnArrival.value && !reachedGoal) {
                    // don't be active when the user logs back in
                    this.onLostControl();
                    if (ctx.world() instanceof ClientLevel clientLevel) {
                        clientLevel.disconnect(Component.literal("[HiveMind] Arrived at goal!"));
                    }
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                reachedGoal = true;

                // we are goingToLandingSpot and we are in the last node of the path
                if (this.goingToLandingSpot && landingSpot != null) {
                    this.state = State.LANDING;
                    logDirect("Above the landing spot, landing...");
                }
            }
        }

        if(goingToLandingSpot&&landingSpot!=null&&ctx.player().isFallFlying()&&state!=State.LANDING){
            double dx=ctx.player().getX()-(landingSpot.x+.5),dz=ctx.player().getZ()-(landingSpot.z+.5);
            if(dx*dx+dz*dz<=36&&ctx.player().getY()<=landingSpot.y+6&&ctx.player().getY()>=landingSpot.y-landingColumnHeight+1)state=State.LANDING;
        }
        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = this.landingSpot != null ? this.landingSpot : behavior.pathManager.path.getLast();
            if (ctx.player().isFallFlying() && endPos != null) {
                Vec3 from = ctx.player().position();
                Vec3 to = new Vec3(((double) endPos.x) + 0.5, from.y, ((double) endPos.z) + 0.5);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                hivemind.getLookBehavior().updateTarget(new Rotation(rotation.getYaw(), 0), false); // this will be overwritten, probably, by behavior tick

                if (ctx.player().position().y < endPos.y - this.landingColumnHeight) {
                    logDirect("bad landing spot, trying again...");
                    landingSpotIsBad(endPos);
                }
            }
        }

        if (ctx.player().isFallFlying()) {
            behavior.landingMode = this.state == State.LANDING;
            this.goal = null;
            hivemind.getInputOverrideHandler().clearAllKeys();
            if (this.behavior.npfContext.tryAcquireReadLock()) {
                try {
                    behavior.tick();
                } finally {
                    this.behavior.npfContext.releaseReadLock();
                }
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        } else if (this.state == State.LANDING) {
            if (ctx.playerMotion().multiply(1, 0, 1).length() > 0.001) {
                logDirect("Landed, but still moving, waiting for velocity to die down... ");
                hivemind.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            logDirect("Done :)");
            hivemind.getInputOverrideHandler().clearAllKeys();
            this.onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround() && HiveMind.settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (shouldLandForSafety()) {
                logDirect("Not taking off, because elytra durability or fireworks are so low that I would immediately emergency land anyway.");
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = hivemind.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect(AUTO_JUMP_FAILURE_MSG);
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new WalkOffCalculationContext(hivemind));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = hivemind.getPathingBehavior().getCurrent();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            final boolean canStartFlying = ctx.player().getDeltaMovement().y < -0.377
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                hivemind.getPathingBehavior().secretInternalSegmentCancel();
            }
            hivemind.getInputOverrideHandler().clearAllKeys();
            // TODO 1.21.5: replace `ctx.player().getDeltaMovement().y < -0.377` with `ctx.player().fallDistance > 1.0f`
            if (ctx.player().getDeltaMovement().y < -0.377) {
                hivemind.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean groundBelowLaunch(){
        BlockPos feet=ctx.player().blockPosition();
        for(int down=1;down<=4;down++){
            BlockPos block=feet.below(down);if(!ctx.world().hasChunkAt(block))return false;
            BlockState state=ctx.world().getBlockState(block);
            if(!state.getFluidState().isEmpty())return false;
            if(!state.getCollisionShape(ctx.world(),block).isEmpty())return true;
        }
        return false;
    }

    public void landingSpotIsBad(BetterBlockPos endPos) {
        badLandingSpots.add(endPos);
        goingToLandingSpot = false;
        this.landingSpot = null;
        this.landingSearchState = null;
        this.landingRetry.reset();
        this.state = State.FLYING;
    }

    private void destroyBehaviorAsync() {
        ElytraBehavior behavior = this.behavior;
        if (behavior != null) {
            this.behavior = null;
            HiveMind.getExecutor().execute(() -> {
                behavior.destroy();
            });
        }
    }

    @Override
    public double priority() {
        return travelOwner==null?0:emergencyTravel?-0.05:ctx.player()!=null&&ctx.player().isFallFlying()?-0.05:-0.8;
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        if (this.npfContext == null) return;

        ChunkSource chunkProvider = ctx.world().getChunkSource();
        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    npfContext.queueForPacking(chunk);
                }
            }
        }
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public List<BetterBlockPos> getPath() {
        return this.behavior != null ? behavior.pathManager.getPath() : Collections.emptyList();
    }

    @Override
    public void pathTo(BlockPos destination) {
        travelOwner=null;autonomousTravel=emergencyTravel=false;takeoffPoint=null;takeoffState=null;
        if (!isSupportedPos(destination)) {
            throw new IllegalArgumentException("The goal must be within bounds to use elytra flight.");
        }

        if (ctx.player() != null && !isSupportedPos(ctx.playerFeet())) {
            throw new IllegalArgumentException("The player must be within bounds to use elytra flight.");
        }

        this.pathTo0(destination, false);
    }

    private void pathTo0(BlockPos destination, boolean appendDestination) {
        if (ctx.player() == null) {
            return;
        }
        IHiveMindProcess owner=this.travelOwner;boolean landing=this.requestedLanding,emergency=this.emergencyTravel;
        BlockPos takeoff=this.takeoffPoint;TakeoffState progress=this.takeoffState;
        this.onLostControl(false);this.travelOwner=owner;this.requestedLanding=landing;this.emergencyTravel=emergency;this.takeoffPoint=takeoff;this.takeoffState=progress;
        this.predictingTerrain = ctx.player().level().dimension() == Level.NETHER && HiveMind.settings().elytraPredictTerrain.value;
        this.allowTight = HiveMind.settings().elytraAllowTightSpaces.value;
        this.allowAboveBuildLimit = HiveMind.settings().elytraAllowAboveBuildLimit.value;
        this.allowAboveRoof = HiveMind.settings().elytraAllowAboveRoof.value;
        this.behavior = new ElytraBehavior(this.hivemind, this, getNpfContext(), destination, appendDestination);

        if (ctx.world() != null) {
            this.repackChunks();
        }
        if(!deferPathInitialization)this.behavior.pathTo();
    }

    @Override
    public void pathTo(Goal iGoal) {
        final int x;
        final int y;
        final int z;
        if (iGoal instanceof GoalXZ) {
            GoalXZ goal = (GoalXZ) iGoal;
            x = goal.getX();
            // ElytraBehavior will automatically change the destination height depending on if we're above or below the roof
            y = 64;
            z = goal.getZ();
        } else if (iGoal instanceof GoalBlock) {
            GoalBlock goal = (GoalBlock) iGoal;
            x = goal.x;
            y = goal.y;
            z = goal.z;
        } else {
            throw new IllegalArgumentException("The goal must be a GoalXZ or GoalBlock");
        }

        this.pathTo((new BlockPos(x, y, z)));
    }

    private boolean isSupportedPos(BlockPos pos) {
        final boolean isNether = ctx.world().dimension() == Level.NETHER;
        final int minY = ctx.world().dimensionType().minY();
        final int maxY = (isNether && !HiveMind.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, ctx.world().dimensionType().height() + minY);

        final boolean aboveRoof = HiveMind.settings().elytraAllowAboveRoof.value;
        final boolean aboveBuild = HiveMind.settings().elytraAllowAboveBuildLimit.value;

        final boolean enforceMaxY = isNether ? !(aboveRoof && aboveBuild) : !aboveBuild;

        if (pos.getY() < minY) {
            return false;
        }

        return !enforceMaxY || pos.getY() < maxY;
    }

    private boolean shouldLandForSafety() {
        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        FlightPolicy.Mode mode=emergencyTravel?FlightPolicy.Mode.ESCAPE:FlightPolicy.Mode.TRAVEL;
        if (chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() < (travelOwner!=null?FlightPolicy.durability(mode,HiveMind.settings().elytraMinimumDurability.value):HiveMind.settings().elytraMinimumDurability.value)) {
            // elytrabehavior replaces when durability <= minimumDurability, so if durability < minimumDurability then we can reasonably assume that the elytra will soon be broken without replacement
            return true;
        }

        NonNullList<ItemStack> inv = ctx.player().getInventory().getNonEquipmentItems();
        int qty = 0;
        for (int i = 0; i < 36; i++) {
            if (ElytraBehavior.isFireworks(inv.get(i))) {
                qty += inv.get(i).getCount();
            }
        }
        if (qty <= (travelOwner!=null?FlightPolicy.reserve(mode,HiveMind.settings().botFlightRocketReserve.value,HiveMind.settings().elytraMinFireworksBeforeLanding.value):HiveMind.settings().elytraMinFireworksBeforeLanding.value)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            destroyBehaviorAsync();
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IHiveMindProcess procThisTick = hivemind.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }

    /**
     * Custom calculation context which makes the player fall into lava
     */
    public static final class WalkOffCalculationContext extends CalculationContext {

        public WalkOffCalculationContext(IHiveMind hivemind) {
            super(hivemind, true);
            this.allowFallIntoLava = true;
            this.minFallHeight = 8;
            this.maxFallHeightNoWater = 10000;
        }

        @Override
        public double costOfPlacingAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return COST_INF;
        }

        @Override
        public double placeBucketCost() {
            return COST_INF;
        }
    }

    private static boolean isInBounds(Level dim, BlockPos pos) {
        DimensionType dimType = dim.dimensionType();
        int minY = dimType.minY();
        int maxY = (dim.dimension() == Level.NETHER && !HiveMind.settings().elytraAllowAboveRoof.value) ? 127 : Math.min(minY + 384, dimType.height() + minY);
        return pos.getY() >= minY && pos.getY() < maxY;
    }

    private boolean isSafeBlock(Block block) {
        return block instanceof net.minecraft.world.level.block.LeavesBlock
                || block == Blocks.SNOW_BLOCK || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
                || block == Blocks.DIRT || block == Blocks.COARSE_DIRT || block == Blocks.ROOTED_DIRT
                || block == Blocks.ANDESITE || block == Blocks.DIORITE || block == Blocks.GRANITE || block == Blocks.CALCITE || block == Blocks.TUFF
                || block == Blocks.NETHERRACK || block == Blocks.GRAVEL || block == Blocks.SOUL_SAND || block == Blocks.SOUL_SOIL || (block == Blocks.NETHER_BRICKS && HiveMind.settings().elytraAllowLandOnNetherFortress.value)
                || block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.GRASS_BLOCK || block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.TERRACOTTA
                || block == Blocks.SNOW || block == Blocks.ICE || block == Blocks.MYCELIUM || block == Blocks.PODZOL
                || block == Blocks.DARK_OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.END_STONE || block == Blocks.BEDROCK
                || block == Blocks.OBSIDIAN || block == Blocks.COBBLESTONE;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return isSafeBlock(ctx.world().getBlockState(pos).getBlock());
    }

    private boolean isAtEdge(BlockPos pos) {
        return !isSafeBlock(pos.north())
                || !isSafeBlock(pos.south())
                || !isSafeBlock(pos.east())
                || !isSafeBlock(pos.west())
                // corners
                || !isSafeBlock(pos.north().west())
                || !isSafeBlock(pos.north().east())
                || !isSafeBlock(pos.south().west())
                || !isSafeBlock(pos.south().east());
    }

    private boolean isColumnAir(BlockPos landingSpot, int minHeight) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(landingSpot.getX(), landingSpot.getY(), landingSpot.getZ());
        final int maxY = mut.getY() + minHeight;
        for (int y = mut.getY() + 1; y <= maxY; y++) {
            mut.set(mut.getX(), y, mut.getZ());
            if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasAirBubble(BlockPos pos) {
        final int radius = 4; // Half of the full width, rounded down, as we're counting blocks in each direction from the center
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    mut.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    if (!(ctx.world().getBlockState(mut).getBlock() instanceof AirBlock)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private BetterBlockPos checkLandingSpot(BlockPos pos, LongOpenHashSet checkedSpots) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(pos.getX(), pos.getY(), pos.getZ());
        while (mut.getY() >= ctx.world().dimensionType().minY()) {
            if (checkedSpots.contains(mut.asLong())) {
                return null;
            }
            checkedSpots.add(mut.asLong());
            Block block = ctx.world().getBlockState(mut).getBlock();

            if (isSafeBlock(block)) {
                if (!isAtEdge(mut)) {
                    return new BetterBlockPos(mut);
                }
                return null;
            } else if (!ctx.world().getBlockState(mut).isAir()) {
                return null;
            }
            mut.set(mut.getX(), mut.getY() - 1, mut.getZ());
        }
        return null; // void
    }

    private BetterBlockPos findSafeLandingSpot(BetterBlockPos start) {
        long now=System.currentTimeMillis();if(!landingRetry.canSearch(now))return null;
        final boolean useHeightmap = ctx.player().getY() > ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, start.getX(), start.getZ());
        if (this.landingSearchState == null || this.landingSearchState.exhausted || !this.landingSearchState.isCompatible(start, useHeightmap)) {
            this.landingSearchState = new LandingSearchState(start, this.behavior.destination, useHeightmap);
        } else {
            this.landingSearchState.updateStartPosition(start);
        }

        BetterBlockPos landingSpot = this.landingSearchState.advance();
        if(landingSpot!=null)this.landingSearchState=null;
        else if(this.landingSearchState.exhausted)this.landingRetry.exhausted(now);
        return landingSpot;
    }

    private boolean isChunkLoaded(BetterBlockPos pos) {
        return ctx.world().getChunkSource().hasChunk(pos.x >> 4, pos.z >> 4);
    }

    private final class LandingSearchState {
        private final BetterBlockPos origin;
        private final boolean useHeightmap;
        private final Queue<BetterBlockPos> queue;
        private final Set<BetterBlockPos> visited = new HashSet<>();
        private final LongOpenHashSet checkedPositions = new LongOpenHashSet();
        private boolean exhausted;

        private LandingSearchState(BetterBlockPos origin, BetterBlockPos dest, boolean useHeightmap) {
            this.origin = origin;
            this.useHeightmap = useHeightmap;

            final BetterBlockPos target = isChunkLoaded(dest) ? dest : origin;
            this.queue = new PriorityQueue<>(Comparator.<BetterBlockPos>comparingInt(pos -> (pos.x - target.x) * (pos.x - target.x) + (pos.z - target.z) * (pos.z - target.z)).thenComparingInt(pos -> -pos.y));
            // A destination can be inside terrain. Always seed the actual player air space too.
            if(this.visited.add(origin))this.queue.add(origin);
            if(this.visited.add(origin.above()))this.queue.add(origin.above());
            if(this.visited.add(target))this.queue.add(target);
        }

        private boolean isCompatible(BetterBlockPos start, boolean useHeightmap) {
            // Restart if we've moved more than a chunk so the priority adjusts and newly loaded chunks get revisited
            return this.useHeightmap == useHeightmap && this.origin.distanceSq(start) <= (16 * 16);
        }

        private void updateStartPosition(BetterBlockPos start) {
            if (this.visited.add(start)) {
                this.queue.add(start);
            }
        }

        private BetterBlockPos advance() {
            final long deadline = System.nanoTime() + LANDING_SEARCH_BUDGET_NANOS;
            while (!this.queue.isEmpty()) {
                if(this.visited.size()>32768){this.exhausted=true;return null;}
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                BetterBlockPos qPos = this.queue.poll();
                if (this.origin.distanceSq(qPos)>128*128||!isChunkLoaded(qPos)) {
                    continue;
                }
                BetterBlockPos landing = this.useHeightmap ? this.advanceHeightmap(qPos) : this.advanceUnderground(qPos);
                if (landing != null) {
                    return landing;
                }
            }
            this.exhausted = true;
            return null;
        }

        private BetterBlockPos advanceUnderground(BetterBlockPos pos) {
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).isAir()) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = SHORT_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot, landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
                if (this.visited.add(pos.above())) this.queue.add(pos.above());
                if (this.visited.add(pos.below())) this.queue.add(pos.below());
            }
            return null;
        }

        private BetterBlockPos advanceHeightmap(BetterBlockPos qPos) {
            int height = ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING, qPos.getX(), qPos.getZ());
            BetterBlockPos pos = new BetterBlockPos(qPos.getX(), height + 1, qPos.getZ());
            if (isInBounds(ctx.world(), pos) && ctx.world().getBlockState(pos).isAir()) {
                BetterBlockPos actualLandingSpot = checkLandingSpot(pos, this.checkedPositions);
                if (actualLandingSpot != null) {
                    landingColumnHeight = ctx.playerFeet().y - actualLandingSpot.y < LONG_LANDING_COLUMN_HEIGHT ? SHORT_LANDING_COLUMN_HEIGHT : LONG_LANDING_COLUMN_HEIGHT;
                    if (isColumnAir(actualLandingSpot,landingColumnHeight) && hasAirBubble(actualLandingSpot.above(landingColumnHeight)) && !badLandingSpots.contains(actualLandingSpot.above(landingColumnHeight))) {
                        return actualLandingSpot.above(landingColumnHeight);
                    }
                }
                if (this.visited.add(pos.north())) this.queue.add(pos.north());
                if (this.visited.add(pos.east())) this.queue.add(pos.east());
                if (this.visited.add(pos.south())) this.queue.add(pos.south());
                if (this.visited.add(pos.west())) this.queue.add(pos.west());
            }
            return null;
        }
    }

    private NetherPathfinderContext getNpfContext() {
        if(this.npfContext == null) {
            npfSema.acquireUninterruptibly();
            this.npfContext = new NetherPathfinderContext(
                    HiveMind.settings().elytraNetherSeed.value,
                    HiveMind.settings().elytraUseCache.value ? hivemind.getWorldProvider().getCurrentWorld().directory.resolve("cache") : null,
                    ctx.world()
            );
        }
        return this.npfContext;
    }

    private void destroyNpfContextAsync() {
        NetherPathfinderContext npf = this.npfContext;
        if (npf != null) {
            this.npfContext = null;
            HiveMind.getExecutor().execute(() -> {
                npf.destroy();
                npfSema.release();
            });
        }
    }
}

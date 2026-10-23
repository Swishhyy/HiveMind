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
import hivemind.utils.network.TeamPackets.Kind;
import hivemind.utils.bot.BotItems;
import hivemind.api.HiveMindAPI;
import hivemind.api.pathing.goals.*;
import hivemind.api.process.IMineProcess;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.*;
import hivemind.api.utils.input.Input;
import hivemind.cache.CachedChunk;
import hivemind.pathing.movement.CalculationContext;
import hivemind.pathing.movement.MovementHelper;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.BlockStateInterface;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.MiningWasteCleanup;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

import static hivemind.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends HiveMindProcessHelper implements IMineProcess {

    private volatile BlockOptionalMetaLookup filter;
    private volatile List<BlockPos> knownOreLocations;
    private List<BlockPos> blacklist; // inaccessible
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;
    private int depositedQuantity;
    private String miningDimension;
    private String companionItem;
    private volatile long jobEpoch;
    private boolean needsRescan;
    private volatile boolean scanFailed;
    private final MiningWasteCleanup wasteCleanup;
    private List<int[]> zoneExploration = List.of();
    private int zoneStep;

    public MineProcess(HiveMind hivemind) {
        super(hivemind);
        this.wasteCleanup = new MiningWasteCleanup(hivemind);
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        hivemind.getSculkProcess().refresh();
        if (hivemind.getSculkProcess().isActive()) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (desiredQuantity > 0) {
            int curr = companionItem != null ? BotItems.count(ctx, companionItem)
                    : ctx.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if ((long) curr + depositedQuantity >= desiredQuantity) {
                if (companionItem != null) {
                    cancel();
                    hivemind.getCompanionProcess().mined();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                hivemind.getJobManager().miningFinished();
                logDirect("Have " + ((long) curr + depositedQuantity) + " valid items including confirmed deposits");
                if (HiveMind.settings().mineAutoDeposit.value) {
                    try {
                        if (MiningRouteAccess.deposit(ctx) != null) hivemind.getDepositProcess().startFinalDeposit();
                    } catch (IllegalStateException e) {
                        hivemind.getJobManager().fail("Final deposit could not start: " + e.getMessage());
                        logDirect("Mining complete; final deposit could not start: " + e.getMessage());
                    }
                }
                cancel();
                return hivemind.getDepositProcess().isActive()
                        ? new PathingCommand(null, PathingCommandType.REQUEST_PAUSE) : null;
            }
        }
        if (wasteCleanup.tick(isSafeToCancel, filter)) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (MiningRouteAccess.locations(ctx).zonesEnabled() && MiningRouteAccess.locations(ctx).zone(miningDimension) == null) {
            hivemind.getJobManager().fail("Set a mining zone in " + miningDimension + " or use minezone off");
            logDirect("Mining stopped: no boundary exists in the target dimension " + miningDimension); cancel(); return null;
        }
        if (hivemind.getSurvivalProcess().beforeMining()) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        if (!MiningRouteAccess.dimension(ctx).equals(miningDimension)) {
            try {
                if (!HiveMind.settings().mineTravelBetweenDimensions.value) throw new IllegalStateException("dimension travel is disabled");
                hivemind.getPortalTravelProcess().start(miningDimension);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            } catch (IllegalStateException e) {
                hivemind.getJobManager().fail("Mining: " + e.getMessage());
                logDirect("Mining stopped: " + e.getMessage());
                cancel();
                return null;
            }
        }
        if (needsRescan) {
            needsRescan = false;
            rescan(new ArrayList<>(), new CalculationContext(hivemind), jobEpoch, filterFilter(),
                    new ArrayList<>(blacklist), droppedItemsScan());
        }
        if (scanFailed) {
            hivemind.getJobManager().fail("No known targets and mining exploration is disabled");
            logDirect("No known locations for " + filter + "; exploration is disabled");
            cancel();
            return null;
        }
        try {
            if (hivemind.getDepositProcess().shouldStartAutomatic()) {
                hivemind.getDepositProcess().start(false);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } catch (IllegalStateException e) {
            hivemind.getJobManager().fail("Automatic deposit: " + e.getMessage());
            logDirect("Cannot start automatic deposit: " + e.getMessage());
            cancel();
            return null;
        }
        if (companionItem != null && ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).noneMatch(ItemStack::isEmpty)) {
            logDirect("Resource inventory is full before the requested ingredient quantity was reached");
            cancel();
            return null;
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && HiveMind.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (HiveMind.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(blacklist::add);
                knownOreLocations.removeIf(blacklist::contains);
            } else {
                hivemind.getJobManager().fail("No usable path to the requested mining targets");
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (HiveMind.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();
        int mineGoalUpdateInterval = HiveMind.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            CalculationContext context = new CalculationContext(hivemind, true);
            long epoch = jobEpoch;
            BlockOptionalMetaLookup target = filterFilter();
            List<BlockPos> excluded = new ArrayList<>(blacklist);
            List<BlockPos> drops = droppedItemsScan();
            HiveMind.getExecutor().execute(() -> rescan(curr, context, epoch, target, excluded, drops));
        }
        if (HiveMind.settings().legitMine.value) {
            if (!addNearby()) {
                hivemind.getJobManager().fail("No reachable local mining targets in legitMine mode");
                cancel();
                return null;
            }
        }
        List<BlockPos> coordinated=hivemind.getNetwork().team().mineTargets(curr);
        if(hivemind.getNetwork().team().enabled()&&!curr.isEmpty()&&coordinated.isEmpty()) { hivemind.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE); }
        Optional<BlockPos> shaft = coordinated.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> pos.getY() >= ctx.playerFeet().getY())
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        hivemind.getInputOverrideHandler().clearAllKeys();
        if (shaft.isPresent() && ctx.player().onGround()) {
            BlockPos pos = shaft.get();
            if (plausibleToBreak(new CalculationContext(hivemind), pos)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent() && isSafeToCancel) {
                    hivemind.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        hivemind.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        PathingCommand command = updateGoal();
        if (command == null) {
            hivemind.getJobManager().fail("No remaining mining targets or exploration goal");
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + HiveMind.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = HiveMind.settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(hivemind);
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, HiveMind.settings().mineMaxOreLocationsCount.value, blacklist, droppedItemsScan());
            knownOreLocations=locs2;
            locs2=hivemind.getNetwork().team().mineTargets(locs2);
            if(locs2.isEmpty())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
            final List<BlockPos> goals=locs2;
            // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
            Goal goal = new GoalComposite(locs2.stream().map(loc -> coalesce(loc, goals, context)).toArray(Goal[]::new));
            return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        // we don't know any ore locations at the moment
        if (!legit && !HiveMind.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = filter.has(Blocks.ANCIENT_DEBRIS) ? Math.max(5, Math.min(120, HiveMind.settings().ancientDebrisYLevel.value))
                : HiveMind.settings().legitMineYLevel.value;
        if (MiningRouteAccess.locations(ctx).zonesEnabled()) {
            var zone = MiningRouteAccess.locations(ctx).zone(MiningRouteAccess.dimension(ctx));
            if (zone == null) return null;
            if (zoneExploration.isEmpty()) zoneExploration = zone.exploration(ctx.playerFeet().getX(), y, ctx.playerFeet().getZ());
            while (zoneStep < zoneExploration.size()) {
                int[] point = zoneExploration.get(zoneStep);
                GoalNear goal = new GoalNear(new BlockPos(point[0],point[1],point[2]),1);
                if (!goal.isInGoal(ctx.playerFeet())) return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                zoneStep++;
            }
            hivemind.getJobManager().fail("No more known targets or exploration points in the mining zone");
            return null;
        }
        if (branchPoint == null) {
            if (filter.has(Blocks.ANCIENT_DEBRIS) && ctx.playerFeet().getY() != y) {
                return new PathingCommand(new GoalYLevel(y), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            /*if (!hivemind.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if(hivemind.getNetwork().team().enabled()&&hivemind.getNetwork().team().groupSize(Kind.MINE,null)>1){
            double heading=hivemind.getNetwork().team().heading(Kind.MINE,null);
            BlockPos point=new BlockPos(branchPoint.getX()+(int)Math.round(Math.cos(heading)*96),y,branchPoint.getZ()+(int)Math.round(Math.sin(heading)*96));
            if(ctx.playerFeet().distSqr(point)<64)branchPoint=ctx.playerFeet();
            return new PathingCommand(new GoalNear(point,4),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void rescan(List<BlockPos> already, CalculationContext context, long epoch,
                        BlockOptionalMetaLookup target, List<BlockPos> excluded, List<BlockPos> dropped) {
        if (target == null || jobEpoch != epoch) {
            return;
        }
        if (HiveMind.settings().legitMine.value) {
            return;
        }
        List<BlockPos> locs = searchWorld(context, target, HiveMind.settings().mineMaxOreLocationsCount.value, already, excluded, dropped);
        locs.addAll(dropped);
        locs.removeIf(pos -> !context.insideMiningBoundary(pos.getX(),pos.getY(),pos.getZ()));
        synchronized (this) {
            // A scan from an old world/job cannot publish targets into the current mining job.
            if (jobEpoch != epoch || filter == null) return;
            scanFailed = locs.isEmpty() && !HiveMind.settings().exploreForBlocks.value;
            knownOreLocations = locs;
        }
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (HiveMind.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(hivemind.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!HiveMind.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    SettingsUtil.maybeCensor(x),
                    SettingsUtil.maybeCensor(y),
                    SettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        if (!HiveMind.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        ret.addAll(anticipatedDrops.keySet());
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                BetterBlockPos pf = ctx.hivemind.getPlayerContext().playerFeet();

                // maxRegionDistanceSq 2 means adjacent directly or adjacent diagonally; nothing further than that
                locs.addAll(ctx.worldData.getCachedWorld().getLocationsOf(
                        BlockUtils.blockToString(block),
                        HiveMind.settings().maxCachedWorldScanCount.value,
                        pf.x,
                        pf.z,
                        2
                ));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped);

        if (!untracked.isEmpty() || (HiveMind.settings().extendCacheOnThreshold.value && locs.size() < max)) {
            locs.addAll(HiveMindAPI.getProvider().getWorldScanner().scanChunkRadius(
                    ctx.getHiveMind().getPlayerContext(),
                    filter,
                    max,
                    10,
                    32
            )); // maxSearchRadius is NOT sq
        }

        locs.addAll(alreadyKnown);

        return prune(ctx, locs, filter, max, blacklist, dropped);
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((HiveMind.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(hivemind), knownOreLocations, filter, HiveMind.settings().mineMaxOreLocationsCount.value, blacklist, dropped);
        return true;
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        dropped.removeIf(drop -> {
            for (BlockPos pos : locs2) {
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) { // TODO maybe drop also has to be supported? no lava below?
                    return true;
                }
            }
            return false;
        });
        List<BlockPos> locs = locs2
                .stream()
                .distinct()
                .filter(pos -> ctx.insideMiningBoundary(pos.getX(),pos.getY(),pos.getZ()))

                // remove any that are within loaded chunks that aren't actually what we want
                .filter(pos -> !ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ()) || filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) || dropped.contains(pos))

                // remove any that are implausible to mine (encased in bedrock, or touching lava)
                .filter(pos -> MineProcess.plausibleToBreak(ctx, pos))

                .filter(pos -> {
                    if (HiveMind.settings().allowOnlyExposedOres.value) {
                        return isNextToAir(ctx, pos);
                    } else {
                        return true;
                    }
                })

                .filter(pos -> pos.getY() >= HiveMind.settings().minYLevelWhileMining.value + ctx.world.dimensionType().minY())

                .filter(pos -> pos.getY() <= HiveMind.settings().maxYLevelWhileMining.value)

                .filter(pos -> !blacklist.contains(pos))

                .sorted(Comparator.comparingDouble(ctx.getHiveMind().getPlayerContext().player().blockPosition()::distSqr))
                .collect(Collectors.toList());

        if (locs.size() > max) {
            return locs.subList(0, max);
        }
        return locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        int radius = HiveMind.settings().allowOnlyExposedOresDistance.value;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        BlockState state = ctx.bsi.get0(pos);
        if (ctx.breakCostMultiplierAt(pos.getX(), pos.getY(), pos.getZ(), state) >= COST_INF) {
            return false;
        }
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(Arrays.stream(blocks)
                .map(name -> name.equalsIgnoreCase("netherite") || name.equalsIgnoreCase("minecraft:netherite")
                        ? "minecraft:ancient_debris" : name).toArray(String[]::new)));
    }

    @Override
    public synchronized void mine(int quantity, BlockOptionalMetaLookup filter) {
        this.companionItem = null;
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.desiredQuantity = quantity;
        this.depositedQuantity = 0;
        this.miningDimension = this.filter == null ? null : (this.filter.has(Blocks.ANCIENT_DEBRIS)
                && HiveMind.settings().mineTravelBetweenDimensions.value ? WorldRoutes.NETHER : MiningRouteAccess.dimension(ctx));
        invalidateWorldTargets();
    }

    public synchronized void mineForCompanion(String item, int quantity, String dimension, List<String> blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks.toArray(String[]::new)));
        if (filter == null) throw new IllegalStateException("Breaking the requested resource is disabled");
        this.companionItem = item;
        this.miningDimension = dimension;
    }

    /** Preserve the resource request and quantity credit, but discard every position from the previous world. */
    public synchronized void invalidateWorldTargets() {
        wasteCleanup.reset();
        zoneExploration = List.of(); zoneStep = 0;
        jobEpoch++;
        needsRescan = filter != null;
        scanFailed = false;
        tickCount = 0;
        this.knownOreLocations = new ArrayList<>();
        this.blacklist = new ArrayList<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
    }

    private BlockOptionalMetaLookup filterFilter() {
        return filter;
    }

    /** Count only transfers confirmed by the server, preserving quantity goals across chest trips. */
    public void recordDeposit(ItemStack stack, int count) {
        if (isActive() && companionItem == null && filter.has(stack)) {
            depositedQuantity = (int) Math.min(Integer.MAX_VALUE, (long) depositedQuantity + count);
            hivemind.getJobManager().recordDeposit(depositedQuantity);
        }
    }

    public boolean targetsAncientDebris() { return filter != null && filter.has(Blocks.ANCIENT_DEBRIS); }
    public void restoreDepositCredit(int count) { depositedQuantity = Math.max(0, count); }
    public int jobProgress() {
        return depositedQuantity + (filter == null || ctx.player() == null ? 0 : ctx.player().getInventory().getNonEquipmentItems().stream()
                .filter(filter::has).mapToInt(ItemStack::getCount).sum());
    }

    public BlockOptionalMetaLookup depositTargets() {
        return filter;
    }

    public boolean isCleaningWaste() {
        return wasteCleanup.isActive();
    }
}

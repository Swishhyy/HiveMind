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
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.mining.DepositPolicy;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.mining.MiningWasteCleanup;
import baritone.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.nio.file.Path;

/** A temporary mining interruption. It never drops items or transfers worn equipment. */
public final class DepositProcess extends BaritoneProcessHelper {
    private enum Stage { IDLE, TRAVEL, OPEN, TRANSFER, RETURN }

    private Stage stage = Stage.IDLE;
    private Path tripRoot;
    private String depositDimension;
    private String returnDimension;
    private BlockPos chest;
    private BlockPos returnTo;
    private boolean depositAll;
    private BlockOptionalMetaLookup miningOutputs;
    private AbstractContainerMenu openedMenu;
    private boolean openRequested;
    private int stageTicks;
    private int pendingSlot = -1;
    private ItemStack pendingStack;
    private long contentRevision;
    private long openRevision;
    private long pendingRevision;
    private int synchronizedMenuId = -1;
    private int pendingTicks;
    private int movedItems;
    private int storageId;
    private final List<WorldRoutes.Storage> remainingChests = new ArrayList<>();
    private final Set<Integer> refusedSlots = new HashSet<>();

    public DepositProcess(Baritone baritone) {
        super(baritone);
    }

    public static boolean supports(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
                || block instanceof CopperChestBlock;
    }

    public boolean shouldStartAutomatic() {
        if (isActive() || !Baritone.settings().mineAutoDeposit.value || ctx.player() == null) {
            return false;
        }
        long empty = ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).filter(ItemStack::isEmpty).count();
        int threshold = Math.max(0, Math.min(35, Baritone.settings().mineDepositFreeSlots.value));
        return empty <= threshold && MiningRouteAccess.deposit(ctx) != null && firstTransferable(false).isPresent();
    }

    public void start(boolean all) {
        if (isActive()) {
            throw new IllegalStateException("A deposit trip is already running");
        }
        if (MiningRouteAccess.deposits(ctx).isEmpty()) throw new IllegalStateException("Look at a chest and run deposit add first");
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
            throw new IllegalStateException("Close the current container before starting a deposit trip");
        }
        BlockPos feet = ctx.playerFeet();
        remainingChests.clear();
        remainingChests.addAll(MiningRouteAccess.store(ctx).orderedDeposits(MiningRouteAccess.dimension(ctx),
                feet.getX(), feet.getY(), feet.getZ(), Baritone.settings().mineTravelBetweenDimensions.value));
        if (remainingChests.isEmpty()) throw new IllegalStateException("No saved storage is reachable in a supported dimension; check dimension travel settings");
        miningOutputs = baritone.getMineProcess().depositTargets();
        tripRoot = MiningRouteAccess.root(ctx);
        returnDimension = MiningRouteAccess.dimension(ctx);
        if (all) {
            baritone.getMineProcess().cancel();
        }
        returnTo = baritone.getMineProcess().isActive() ? ctx.playerFeet().immutable() : null;
        depositAll = all;
        movedItems = 0;
        refusedSlots.clear();
        chooseNextStorage();
        logDirect("Returning to storage #" + storageId + (all ? " (empty main inventory)" : " (keep supplies)"));
    }

    public void startFinalDeposit() {
        start(false);
        returnTo = null;
        returnDimension = null;
    }

    @Override
    public boolean isActive() {
        return stage != Stage.IDLE;
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return DEFAULT_PRIORITY + 0.5; // Above mining, below the user's pause command.
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        try {
            return depositTick(calcFailed, isSafeToCancel);
        } catch (IllegalStateException e) {
            return fail(e.getMessage());
        }
    }

    private PathingCommand depositTick(boolean calcFailed, boolean isSafeToCancel) {
        if (ctx.player() == null || ctx.world() == null || !tripRoot.equals(MiningRouteAccess.root(ctx))) {
            return fail("the server or save changed");
        }
        if (calcFailed) {
            return stage == Stage.RETURN ? fail("no usable route to the mining site") : tryNextStorage("storage #" + storageId + " is unreachable");
        }
        if (++stageTicks > ((stage == Stage.TRAVEL || stage == Stage.RETURN) ? 12000 : 1800)) {
            return stage == Stage.RETURN ? fail("return trip timed out") : tryNextStorage("storage #" + storageId + " timed out");
        }
        String currentDimension = MiningRouteAccess.dimension(ctx);
        String requiredDimension = stage == Stage.RETURN ? returnDimension : depositDimension;
        if (!currentDimension.equals(requiredDimension)) {
            if (!Baritone.settings().mineTravelBetweenDimensions.value) return fail("dimension travel is disabled");
            baritone.getPortalTravelProcess().start(requiredDimension);
            return pause();
        }
        if (stage != Stage.RETURN && ctx.world().hasChunkAt(chest)
                && !supports(ctx.world().getBlockState(chest).getBlock())) {
            return tryNextStorage("storage #" + storageId + " is missing or was replaced");
        }
        if (stage == Stage.TRAVEL) {
            GoalGetToBlock goal = new GoalGetToBlock(chest);
            if (goal.isInGoal(ctx.playerFeet()) && isSafeToCancel) {
                transition(Stage.OPEN);
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        if (stage == Stage.RETURN) {
            GoalNear goal = new GoalNear(returnTo, 1);
            if (goal.isInGoal(ctx.playerFeet()) && isSafeToCancel) {
                onLostControl();
                logDirect("Back at the mining site; resuming mining");
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(goal, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        if (!isSafeToCancel) {
            return pause();
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        ctx.playerController().resetBlockRemoving();
        if (stage == Stage.OPEN) {
            return openChest();
        }
        return transfer();
    }

    private PathingCommand openChest() {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu != ctx.player().inventoryMenu) {
            if (!openRequested || !(menu instanceof ChestMenu)) {
                return fail("an unexpected container was opened");
            }
            if (synchronizedMenuId != menu.containerId || contentRevision <= openRevision) {
                return stageTicks > 100 ? fail("the server did not send the storage contents") : pause();
            }
            openedMenu = menu;
            transition(Stage.TRANSFER);
            return pause();
        }
        if (openRequested) {
            return stageTicks > 100 ? tryNextStorage("storage #" + storageId + " could not be opened; it may be blocked or protected") : pause();
        }
        Optional<Rotation> rotation = RotationUtils.reachable(ctx, chest);
        if (rotation.isEmpty()) {
            return tryNextStorage("storage #" + storageId + " is not reachable from the arrival position");
        }
        baritone.getLookBehavior().updateTarget(rotation.get(), true);
        if (ctx.isLookingAt(chest)) {
            openRequested = true;
            openRevision = contentRevision;
            stageTicks = 0;
            ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false));
        }
        return pause();
    }

    private PathingCommand transfer() {
        if (ctx.player().containerMenu != openedMenu || !(openedMenu instanceof ChestMenu)) {
            return fail("the deposit chest was closed during transfer");
        }
        int storageSlots = openedMenu.slots.size() - 36;
        if (storageSlots != 27 && storageSlots != 54) {
            return fail("unsupported chest slot layout");
        }
        if (pendingSlot >= 0) {
            if (++pendingTicks < 5) return pause();
            // Correct client prediction may produce no slot update at all. Reopening requests
            // authoritative contents, including the player's inventory, rather than guessing an acknowledgement.
            if (contentRevision <= pendingRevision || synchronizedMenuId != openedMenu.containerId) {
                closeOwnedMenu();
                openRequested = false;
                transition(Stage.OPEN);
                return pause();
            }
            ItemStack remaining = ctx.player().getInventory().getItem(pendingSlot);
            int remainingCount = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = DepositPolicy.transferred(pendingStack.getCount(), remainingCount);
            if (transferred == 0) {
                refusedSlots.add(pendingSlot);
            } else {
                movedItems += transferred;
                baritone.getMineProcess().recordDeposit(pendingStack, transferred);
                if (!remaining.isEmpty()) {
                    refusedSlots.add(pendingSlot); // A partial stack filled the remaining chest space.
                }
            }
            pendingSlot = -1;
            pendingStack = null;
            return pause();
        }
        Optional<Integer> next = firstTransferable(true);
        if (next.isEmpty()) {
            if (firstTransferable(false).isPresent()) {
                return tryNextStorage("storage #" + storageId + " is full or rejected some items");
            }
            closeOwnedMenu();
            logDirect("Deposited " + movedItems + " items");
            if (returnTo != null && baritone.getMineProcess().isActive()) {
                transition(Stage.RETURN);
            } else {
                onLostControl();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        pendingSlot = next.get();
        pendingStack = ctx.player().getInventory().getItem(pendingSlot).copy();
        boolean hasSpace = false;
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stored = openedMenu.slots.get(i).getItem();
            if (openedMenu.slots.get(i).mayPlace(pendingStack) && (stored.isEmpty()
                    || (ItemStack.isSameItemSameComponents(stored, pendingStack)
                    && stored.getCount() < Math.min(stored.getMaxStackSize(), openedMenu.slots.get(i).getMaxStackSize(pendingStack))))) {
                hasSpace = true;
                break;
            }
        }
        if (!hasSpace) {
            refusedSlots.add(pendingSlot);
            pendingSlot = -1;
            pendingStack = null;
            return pause();
        }
        pendingRevision = contentRevision;
        pendingTicks = 0;
        ctx.playerController().windowClick(openedMenu.containerId, DepositPolicy.menuSlot(pendingSlot, storageSlots),
                0, ContainerInput.QUICK_MOVE, ctx.player());
        return pause();
    }

    private Optional<Integer> firstTransferable(boolean skipRefused) {
        BlockOptionalMetaLookup outputs = miningOutputs == null ? baritone.getMineProcess().depositTargets() : miningOutputs;
        Set<Integer> buildingSlots = MiningWasteCleanup.reservedBuildingSlots(ctx, outputs);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.isEmpty() || (skipRefused && refusedSlots.contains(i))) {
                continue;
            }
            boolean supply = stack.isDamageableItem() || stack.has(DataComponents.FOOD) || stack.has(DataComponents.EQUIPPABLE);
            if (!depositAll && buildingSlots.contains(i)) continue;
            if (!DepositPolicy.retain(i, depositAll, Baritone.settings().depositKeepHotbar.value,
                    Baritone.settings().depositKeepSupplies.value, supply,
                    Baritone.settings().depositKeepItems.value.contains(stack.getItem()), outputs != null && outputs.has(stack))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private PathingCommand fail(String reason) {
        if (baritone.getMineProcess() != null) {
            baritone.getMineProcess().cancel();
        }
        onLostControl();
        logDirect("Deposit stopped: " + reason + ". Mining stopped; no items were dropped.");
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean chooseNextStorage() {
        String dimension = MiningRouteAccess.dimension(ctx);
        WorldRoutes.Storage next = remainingChests.stream().min(Comparator
                .comparingInt((WorldRoutes.Storage storage) -> storage.endpoint().dimension().equals(dimension) ? 0 : 1)
                .thenComparingDouble(storage -> storage.endpoint().dimension().equals(dimension)
                        ? ctx.playerFeet().distSqr(MiningRouteAccess.position(storage.endpoint())) : 0)
                .thenComparingInt(WorldRoutes.Storage::id)).orElse(null);
        if (next == null) return false;
        remainingChests.remove(next);
        storageId = next.id();
        chest = MiningRouteAccess.position(next.endpoint());
        depositDimension = next.endpoint().dimension();
        transition(Stage.TRAVEL);
        return true;
    }

    private PathingCommand tryNextStorage(String reason) {
        suspendForConflict();
        if (!chooseNextStorage()) return fail(reason + "; all saved storage was tried (" + movedItems + " items deposited)");
        logDirect(reason + "; trying storage #" + storageId);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Close our own menu before retreating, preserving the trip and any already-confirmed transfer credit. */
    public void suspendForConflict() {
        if (pendingSlot >= 0 && pendingStack != null && openedMenu != null && contentRevision > pendingRevision
                && synchronizedMenuId == openedMenu.containerId) {
            ItemStack remaining = ctx.player().getInventory().getItem(pendingSlot);
            int count = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = DepositPolicy.transferred(pendingStack.getCount(), count);
            movedItems += transferred;
            baritone.getMineProcess().recordDeposit(pendingStack, transferred);
        }
        closeOwnedMenu();
        pendingSlot = -1;
        pendingStack = null;
        openRequested = false;
        refusedSlots.clear();
        if (stage == Stage.OPEN || stage == Stage.TRANSFER) transition(Stage.TRAVEL);
    }

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /** Called only after a matching server full-content packet has been applied to this player's menu. */
    public void onContainerContents(int containerId) {
        if (ctx.player() != null && containerId == ctx.player().containerMenu.containerId) {
            synchronizedMenuId = containerId;
            contentRevision++;
        }
    }

    private void transition(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private void closeOwnedMenu() {
        if (openedMenu != null && ctx.player() != null && ctx.player().containerMenu == openedMenu) {
            ctx.player().closeContainer();
        }
        openedMenu = null;
    }

    @Override
    public void onLostControl() {
        closeOwnedMenu();
        stage = Stage.IDLE;
        pendingSlot = -1;
        pendingStack = null;
        synchronizedMenuId = -1;
        openRequested = false;
        depositAll = false;
        miningOutputs = null;
        tripRoot = null;
        depositDimension = returnDimension = null;
        returnTo = null;
        refusedSlots.clear();
        remainingChests.clear();
        storageId = 0;
    }

    @Override
    public String displayName0() {
        return "Deposit: " + stage.name().toLowerCase(java.util.Locale.ROOT) + " (storage #" + storageId + ")";
    }
}

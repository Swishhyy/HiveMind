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
import baritone.utils.bot.StorageRule;
import baritone.utils.bot.BotInventory;
import baritone.utils.bot.BotItems;
import java.util.Map;
import baritone.utils.bot.SurvivalPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
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
    private boolean resupply;
    private boolean fetch;
    private Map<String,Integer> fetchTargets = Map.of();
    private String lastFetchError;
    private String lastDepositError;
    private String product;
    private boolean wantFood, wantBlocks, wantPickaxe;
    private boolean pendingWithdrawal;
    private int pendingInventoryCount;
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
    private StorageRule storageRule = StorageRule.DEFAULT;
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

    public void start(boolean all) { start(all,null); }
    public void startProductDeposit(String item) { start(false,item); returnTo = null; returnDimension = null; }
    private void start(boolean all,String onlyProduct) {
        this.product = onlyProduct;
        if (isActive()) {
            throw new IllegalStateException("A deposit trip is already running");
        }
        lastDepositError = null;
        if (MiningRouteAccess.deposits(ctx).isEmpty()) throw new IllegalStateException("Look at a chest and run deposit add first");
        if (ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) {
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
        resupply = fetch = false;
        movedItems = 0;
        refusedSlots.clear();
        if (firstTransferable(false).isEmpty()) { onLostControl(); logDirect("No transferable items to deposit"); return; }
        if (!chooseNextStorage()) { onLostControl(); throw new IllegalStateException("No saved storage rule accepts the transferable items"); }
        logDirect("Returning to storage #" + storageId + (all ? " (empty main inventory)" : " (keep supplies)"));
    }

    public boolean isResupplying() { return resupply && !fetch && isActive(); }
    public String lastFetchError() { return lastFetchError; }
    public String lastDepositError() { return lastDepositError; }
    public void startFetch(Map<String,Integer> targets) {
        if (isActive()) throw new IllegalStateException("A storage trip is already active");
        if (targets.isEmpty() || targets.size()>128 || targets.values().stream().anyMatch(n->n<1||n>1000000)) throw new IllegalArgumentException("Invalid withdrawal request");
        if (ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Close containers and clear the cursor before fetching items");
        MiningRouteAccess.deposits(ctx); BlockPos feet=ctx.playerFeet();
        remainingChests.clear();remainingChests.addAll(MiningRouteAccess.store(ctx).orderedDeposits(MiningRouteAccess.dimension(ctx),feet.getX(),feet.getY(),feet.getZ(),Baritone.settings().mineTravelBetweenDimensions.value));
        remainingChests.removeIf(storage->storage.rule().kind()==StorageRule.Kind.DISABLED);
        if(remainingChests.isEmpty())throw new IllegalStateException("No registered storage is reachable");
        tripRoot=MiningRouteAccess.root(ctx);returnDimension=MiningRouteAccess.dimension(ctx);returnTo=feet.immutable();
        depositAll=false;resupply=fetch=true;product=null;fetchTargets=Map.copyOf(targets);lastFetchError=null;movedItems=0;refusedSlots.clear();chooseNextStorage();
        logDirect("Fetching requested items from registered storage");
    }

    public void startResupply() {
        if (isActive()) throw new IllegalStateException("Finish the current storage trip before resupplying");
        boolean food = BotInventory.needsFood(ctx);
        boolean blocks = BotInventory.needsBlocks(ctx) && baritone.getMineProcess().isActive();
        boolean pick = baritone.getMineProcess().isActive() && BotInventory.slot(ctx,
                stack -> BotInventory.pickaxe(stack, baritone.getMineProcess().targetsAncientDebris())) < 0;
        if (ctx.player().containerMenu != ctx.player().inventoryMenu) throw new IllegalStateException("Close the current container before resupplying");
        MiningRouteAccess.deposits(ctx);
        BlockPos feet = ctx.playerFeet();
        remainingChests.clear();
        remainingChests.addAll(MiningRouteAccess.store(ctx).orderedDeposits(MiningRouteAccess.dimension(ctx),
                feet.getX(), feet.getY(), feet.getZ(), Baritone.settings().mineTravelBetweenDimensions.value));
        remainingChests.removeIf(storage -> storage.rule().kind() == StorageRule.Kind.DISABLED || storage.rule().kind() == StorageRule.Kind.ORES);
        if (remainingChests.isEmpty()) throw new IllegalStateException("No reachable supplies chest; register a chest with storage rule <id> supplies");
        tripRoot = MiningRouteAccess.root(ctx); returnDimension = MiningRouteAccess.dimension(ctx);
        returnTo = ctx.playerFeet().immutable();
        depositAll = false; resupply = true; fetch = false; product = null; wantFood = food; wantBlocks = blocks; wantPickaxe = pick;
        movedItems = 0; refusedSlots.clear();
        if (!chooseNextStorage()) { onLostControl(); throw new IllegalStateException("No supplies storage is available"); }
        logDirect("Resupplying from storage #" + storageId);
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
        if (stage != Stage.RETURN && Baritone.settings().mineAvoidSculk.value
                && baritone.getSculkProcess().snapshot().contains(chest.getX(), chest.getY(), chest.getZ()))
            return tryNextStorage("storage #" + storageId + " is inside a known sculk hazard zone");
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
            ItemStack remaining = pendingWithdrawal ? ItemStack.EMPTY : ctx.player().getInventory().getItem(pendingSlot);
            int remainingCount = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = pendingWithdrawal ? Math.max(0, BotInventory.count(ctx,
                    stack -> ItemStack.isSameItemSameComponents(stack, pendingStack)) - pendingInventoryCount)
                    : DepositPolicy.transferred(pendingStack.getCount(), remainingCount);
            if (transferred == 0) {
                refusedSlots.add(pendingSlot);
            } else {
                movedItems += transferred;
                if (!pendingWithdrawal) baritone.getMineProcess().recordDeposit(pendingStack, transferred);
                if (!remaining.isEmpty()) {
                    refusedSlots.add(pendingSlot); // A partial stack filled the remaining chest space.
                }
            }
            pendingSlot = -1;
            pendingStack = null;
            pendingWithdrawal = false;
            return pause();
        }
        if (resupply) return withdraw(storageSlots);
        Optional<Integer> next = firstTransferable(true, storageRule);
        if (next.isEmpty()) {
            if (firstTransferable(false).isPresent()) {
                return tryNextStorage("storage #" + storageId + " is full or rejected some items");
            }
            return finishTransfer();
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

    private PathingCommand finishTransfer() {
        closeOwnedMenu();
        logDirect((resupply ? "Restocked " : "Deposited ") + movedItems + " items");
        if (returnTo != null && (resupply || baritone.getMineProcess().isActive())) transition(Stage.RETURN);
        else onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean needsSupply(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (fetch) return BotItems.ordinary(stack) && BotItems.count(ctx,BotItems.id(stack)) < fetchTargets.getOrDefault(BotItems.id(stack),0);
        if (wantFood && BotInventory.food(stack)) return BotInventory.count(ctx, BotInventory::food)
                < Math.max(SurvivalPolicy.bounded(Baritone.settings().botMinimumFood.value, 1, 64),
                        SurvivalPolicy.bounded(Baritone.settings().botRestockFood.value, 1, 128));
        if (wantBlocks && BotInventory.building(stack)) return BotInventory.count(ctx, BotInventory::building)
                < Math.max(SurvivalPolicy.bounded(Baritone.settings().botMinimumBlocks.value, 0, 128),
                        SurvivalPolicy.bounded(Baritone.settings().botRestockBlocks.value, 0, 256));
        return wantPickaxe && BotInventory.pickaxe(stack, baritone.getMineProcess().targetsAncientDebris())
                && BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, baritone.getMineProcess().targetsAncientDebris())) < 0;
    }

    private PathingCommand withdraw(int storageSlots) {
        int slot = -1;
        for (int i = 0; i < storageSlots; i++) if (!refusedSlots.contains(i) && needsSupply(openedMenu.slots.get(i).getItem())
                && openedMenu.slots.get(i).mayPickup(ctx.player())) { slot = i; break; }
        if (slot < 0) {
            if (fetch) {
                if (fetchTargets.entrySet().stream().allMatch(entry->BotItems.count(ctx,entry.getKey())>=entry.getValue()) || remainingChests.isEmpty()) return finishTransfer();
                return tryNextStorage("Checking the next storage for requested items");
            }
            boolean enough = (!wantFood || !BotInventory.needsFood(ctx)) && (!wantBlocks || !BotInventory.needsBlocks(ctx))
                    && (!wantPickaxe || BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, baritone.getMineProcess().targetsAncientDebris())) >= 0);
            if (enough) return finishTransfer();
            return tryNextStorage("storage #" + storageId + " has no more required supplies");
        }
        pendingSlot = slot;
        pendingStack = openedMenu.slots.get(slot).getItem().copy();
        pendingWithdrawal = true;
        pendingInventoryCount = BotInventory.count(ctx, item -> ItemStack.isSameItemSameComponents(item, pendingStack));
        pendingRevision = contentRevision; pendingTicks = 0;
        ctx.playerController().windowClick(openedMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, ctx.player());
        return pause();
    }

    private Optional<Integer> firstTransferable(boolean skipRefused) {
        return firstTransferable(skipRefused, StorageRule.DEFAULT);
    }

    private Optional<Integer> firstTransferable(boolean skipRefused, StorageRule rule) {
        BlockOptionalMetaLookup outputs = miningOutputs == null ? baritone.getMineProcess().depositTargets() : miningOutputs;
        Set<Integer> buildingSlots = new HashSet<>(MiningWasteCleanup.reservedBuildingSlots(ctx, outputs));
        if (Baritone.settings().botSurvival.value) buildingSlots.addAll(BotInventory.buildingReserves(ctx, outputs));
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.isEmpty() || (skipRefused && refusedSlots.contains(i))) {
                continue;
            }
            if (product != null) {
                if (BotItems.id(stack).equals(product) && BotItems.ordinary(stack) && accepts(rule,stack)) return Optional.of(i);
                continue;
            }
            if (baritone.getCompanionProcess().reserves(BotItems.id(stack))) continue;
            if (!accepts(rule, stack)) continue;
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

    public static boolean accepts(StorageRule rule, ItemStack stack) {
        return rule.accepts(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getItem() instanceof BlockItem,
                Baritone.settings().mineWasteItems.value.contains(stack.getItem()),
                stack.isDamageableItem() || stack.has(DataComponents.FOOD) || stack.has(DataComponents.EQUIPPABLE) || BotInventory.building(stack)
                        || Baritone.settings().depositKeepItems.value.contains(stack.getItem()));
    }

    private PathingCommand fail(String reason) {
        if (fetch) { onLostControl(); lastFetchError=reason; logDirect("Fetch stopped: "+reason); return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL); }
        if (baritone.getJobManager() != null) baritone.getJobManager().fail("Deposit: " + reason);
        if (resupply && baritone.getSurvivalProcess() != null) baritone.getSurvivalProcess().supplyFailed(reason);
        if (!resupply && baritone.getMineProcess() != null) {
            baritone.getMineProcess().cancel();
        }
        onLostControl();
        lastDepositError = reason;
        logDirect("Storage trip stopped: " + reason + ". Bot work requires attention; no items were dropped.");
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean chooseNextStorage() {
        String dimension = MiningRouteAccess.dimension(ctx);
        WorldRoutes.Storage next = remainingChests.stream().filter(storage -> resupply || firstTransferable(false, storage.rule()).isPresent()).min(Comparator
                .comparingInt((WorldRoutes.Storage storage) -> resupply ? (storage.rule().kind() == StorageRule.Kind.SUPPLIES ? 0 : 1) : (storage.rule().kind() == StorageRule.Kind.ANY ? 1 : 0))
                .thenComparing(Comparator.comparingInt((WorldRoutes.Storage storage) -> storage.rule().priority()).reversed())
                .thenComparingInt(storage -> storage.endpoint().dimension().equals(dimension) ? 0 : 1)
                .thenComparingDouble(storage -> storage.endpoint().dimension().equals(dimension)
                        ? ctx.playerFeet().distSqr(MiningRouteAccess.position(storage.endpoint())) : 0)
                .thenComparingInt(WorldRoutes.Storage::id)).orElse(null);
        if (next == null) return false;
        remainingChests.remove(next);
        storageId = next.id();
        storageRule = next.rule();
        chest = MiningRouteAccess.position(next.endpoint());
        depositDimension = next.endpoint().dimension();
        transition(Stage.TRAVEL);
        return true;
    }

    private PathingCommand tryNextStorage(String reason) {
        suspendForConflict();
        if (!chooseNextStorage()) { if(fetch)return finishTransfer(); return fail(reason + "; all saved storage was tried (" + movedItems + " items deposited)"); }
        logDirect(reason + "; trying storage #" + storageId);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Close our own menu before retreating, preserving the trip and any already-confirmed transfer credit. */
    public void suspendForConflict() {
        if (!pendingWithdrawal && pendingSlot >= 0 && pendingStack != null && openedMenu != null && contentRevision > pendingRevision
                && synchronizedMenuId == openedMenu.containerId) {
            ItemStack remaining = ctx.player().getInventory().getItem(pendingSlot);
            int count = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = DepositPolicy.transferred(pendingStack.getCount(), count);
            movedItems += transferred;
            baritone.getMineProcess().recordDeposit(pendingStack, transferred);
        }
        closeOwnedMenu();
        pendingWithdrawal = false;
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
        if (openedMenu != null && ctx.player() != null && ctx.player().containerMenu == openedMenu && openedMenu.getCarried().isEmpty()) {
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
        resupply = fetch = wantFood = wantBlocks = wantPickaxe = pendingWithdrawal = false;
        fetchTargets = Map.of(); product = null;
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
        return (resupply ? "Resupply: " : "Deposit: ") + stage.name().toLowerCase(java.util.Locale.ROOT) + " (storage #" + storageId + ")";
    }
}

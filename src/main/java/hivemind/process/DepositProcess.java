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
import hivemind.utils.reliability.ActionArbiter;
import hivemind.api.pathing.goals.GoalGetToBlock;
import hivemind.api.pathing.goals.GoalNear;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.Rotation;
import hivemind.api.utils.RotationUtils;
import hivemind.api.utils.BlockOptionalMetaLookup;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.StorageRule;
import hivemind.utils.bot.BotInventory;
import hivemind.utils.bot.BotItems;
import hivemind.utils.bot.ExpeditionLoadout;
import hivemind.utils.network.SupplyReserves;
import java.util.Map;
import hivemind.utils.bot.SurvivalPolicy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import hivemind.utils.mining.DepositPolicy;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.MiningWasteCleanup;
import hivemind.utils.mining.WorldRoutes;
import hivemind.utils.bot.StockBoxTrip;
import hivemind.utils.bot.StorageAccessPolicy;
import hivemind.utils.bot.EmergencySupplies;
import hivemind.utils.bot.BotBaseAccess;
import hivemind.utils.bot.StorageStockLedger;
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
public final class DepositProcess extends HiveMindProcessHelper {
    private enum Stage { IDLE, PORTABLE, STOCK_BOX, TRAVEL, OPEN, TRANSFER, RETURN }

    private Stage stage = Stage.IDLE;
    private final StockBoxTrip stockBoxes=new StockBoxTrip();
    private boolean unpackStarted;
    private boolean portableStarted,portableRecovering,portableOnly,usedPortable;
    private long portableDeadline;
    private Map<String,Integer> portableBefore=Map.of();
    private int pendingBoxEmptySlots;
    private Path tripRoot;
    private String depositDimension;
    private String returnDimension;
    private BlockPos chest;
    private BlockPos returnTo;
    private boolean depositAll;
    private boolean resupply;
    private boolean survivalSupply;
    private boolean fetch;
    private boolean fetchVariants;
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
    private StorageStockLedger observedStock;
    private Path stockWorld;
    private java.util.UUID stockAccount;

    public DepositProcess(HiveMind hivemind) {
        super(hivemind);
    }

    public List<StorageStockLedger.Observation> stockObservations(){
        if(ctx.player()==null||ctx.world()==null)return observedStock==null?List.of():observedStock.recent();
        Path world=MiningRouteAccess.root(ctx);java.util.UUID account=ctx.player().getUUID();
        if(observedStock==null||!world.equals(stockWorld)||!account.equals(stockAccount)){
            observedStock=new StorageStockLedger(world,account);stockWorld=world;stockAccount=account;
        }
        return observedStock.recent();
    }

    /** Read only contents confirmed by the server for this open menu. Stale sightings never authorize a transfer. */
    private void rememberStock(int slots){
        if(slots<27||openedMenu==null||synchronizedMenuId!=openedMenu.containerId)return;
        stockObservations();if(observedStock==null)return;
        Map<String,Integer> counts=new java.util.LinkedHashMap<>();
        for(int i=0;i<slots;i++){
            ItemStack stack=openedMenu.slots.get(i).getItem();if(stack.isEmpty())continue;
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
            if(ExpeditionLoadout.portable(stack)){
                var contents=stack.get(DataComponents.CONTAINER);
                if(contents!=null){
                    var items=net.minecraft.core.NonNullList.<ItemStack>withSize(27,ItemStack.EMPTY);contents.copyInto(items);
                    for(ItemStack item:items)if(!item.isEmpty())counts.merge("inside:"+BuiltInRegistries.ITEM.getKey(item.getItem()),item.getCount()*stack.getCount(),Integer::sum);
                }
            }
        }
        // A full chest can have more than 128 distinct component variants; store only bounded item totals.
        if(counts.size()>128)counts=new java.util.LinkedHashMap<>(counts.entrySet().stream().limit(128).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue,(a,b)->a,java.util.LinkedHashMap::new)));
        observedStock.observe(new WorldRoutes.Endpoint(depositDimension,chest.getX(),chest.getY(),chest.getZ()),
                ctx.world().getBlockState(chest).is(Blocks.ENDER_CHEST),counts,System.currentTimeMillis());
        hivemind.getOperations().journal().note("Storage","Observed "+depositDimension+" "+chest.toShortString()+" ("+counts.size()+" item types)");
    }

    public static boolean supports(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL || block == Blocks.ENDER_CHEST
                || block instanceof CopperChestBlock;
    }

    public boolean shouldStartAutomatic() {
        if (isActive() || !HiveMind.settings().mineAutoDeposit.value || ctx.player() == null) {
            return false;
        }
        long empty = ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).filter(ItemStack::isEmpty).count();
        int threshold = Math.max(0, Math.min(35, HiveMind.settings().mineDepositFreeSlots.value));
        return empty <= threshold && MiningRouteAccess.deposit(ctx) != null && firstTransferable(false).isPresent();
    }

    public void start(boolean all) { start(all,null); }
    public void startProductDeposit(String item) { start(false,item); returnTo = null; returnDimension = null; }
    private void start(boolean all,String onlyProduct) {
        if (isActive()) {
            throw new IllegalStateException("A deposit trip is already running");
        }
        this.product = onlyProduct;
        lastDepositError = null;
        if (MiningRouteAccess.deposits(ctx).isEmpty()) throw new IllegalStateException("Look at a chest and run deposit add first");
        if (ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) {
            throw new IllegalStateException("Close the current container before starting a deposit trip");
        }
        BlockPos feet = ctx.playerFeet();
        remainingChests.clear();
        remainingChests.addAll(MiningRouteAccess.store(ctx).orderedDeposits(MiningRouteAccess.dimension(ctx),
                feet.getX(), feet.getY(), feet.getZ(), HiveMind.settings().mineTravelBetweenDimensions.value));
        if (remainingChests.isEmpty()) throw new IllegalStateException("No saved storage is reachable in a supported dimension; check dimension travel settings");
        miningOutputs = hivemind.getMineProcess().depositTargets();
        tripRoot = MiningRouteAccess.root(ctx);
        returnDimension = MiningRouteAccess.dimension(ctx);
        if (all) {
            hivemind.getMineProcess().cancel();
        }
        returnTo = hivemind.getMineProcess().isActive() ? ctx.playerFeet().immutable() : null;
        depositAll = all;
        resupply = fetch = false;
        movedItems = 0;
        refusedSlots.clear();
        if (firstTransferable(false).isEmpty()) { onLostControl(); logDirect("No transferable items to deposit"); return; }
        if (!chooseNextStorage()) { onLostControl(); throw new IllegalStateException("No saved storage rule accepts the transferable items"); }
        logDirect("Returning to storage #" + storageId + (all ? " (empty main inventory)" : " (keep supplies)"));
    }

    public boolean isResupplying() { return survivalSupply && isActive(); }
    public String lastFetchError() { return lastFetchError; }
    public String lastDepositError() { return lastDepositError; }
    public void startFetch(Map<String,Integer> targets){startFetch(targets,false);}
    public void startExpeditionFetch(Map<String,Integer> targets){startFetch(targets,true);}
    public void startCarriedFetch(Map<String,Integer> targets){if(isActive())throw new IllegalStateException("A storage trip is already active");portableOnly=true;try{startFetch(targets,true);}catch(RuntimeException error){portableOnly=false;throw error;}}
    private int fetchGoal(String id,int target){return fetchVariants?SupplyReserves.refillGoal(id,target):target;}
    private int fetchCount(String id){return fetchVariants?ExpeditionLoadout.count(ctx,id):BotItems.count(ctx,id);}
    private void startFetch(Map<String,Integer> targets,boolean variants) {
        if (isActive()) throw new IllegalStateException("A storage trip is already active");
        if (targets.isEmpty() || targets.size()>128 || targets.values().stream().anyMatch(n->n<1||n>1000000)) throw new IllegalArgumentException("Invalid withdrawal request");
        if (ctx.player().containerMenu != ctx.player().inventoryMenu || !ctx.player().containerMenu.getCarried().isEmpty()) throw new IllegalStateException("Close containers and clear the cursor before fetching items");
        stockBoxes.reset();unpackStarted=false;fetchVariants=variants;
        if(hivemind.getShulkerProcess().hasPortableItems(targets,variants)||hivemind.getShulkerProcess().hasPendingRecovery()){
            tripRoot=MiningRouteAccess.root(ctx);fetchTargets=Map.copyOf(targets);fetch=resupply=true;lastFetchError=null;
            returnDimension=MiningRouteAccess.dimension(ctx);returnTo=ctx.playerFeet().immutable();movedItems=0;
            portableStarted=false;portableRecovering=false;usedPortable=true;portableBefore=Map.of();portableDeadline=System.currentTimeMillis()+120000;
            transition(Stage.PORTABLE);return;
        }
        if(portableOnly)throw new IllegalStateException("Requested carried supplies changed or are no longer available");
        BotBaseAccess.discoverEnderChests(ctx);MiningRouteAccess.deposits(ctx); BlockPos feet=ctx.playerFeet();
        remainingChests.clear();remainingChests.addAll(MiningRouteAccess.store(ctx).orderedDeposits(MiningRouteAccess.dimension(ctx),feet.getX(),feet.getY(),feet.getZ(),HiveMind.settings().mineTravelBetweenDimensions.value));
        remainingChests.removeIf(storage->storage.rule().kind()==StorageRule.Kind.DISABLED);
        if(remainingChests.isEmpty())throw new IllegalStateException("No registered storage is reachable");
        tripRoot=MiningRouteAccess.root(ctx);returnDimension=MiningRouteAccess.dimension(ctx);returnTo=feet.immutable();
        depositAll=false;resupply=fetch=true;product=null;fetchTargets=Map.copyOf(targets);lastFetchError=null;movedItems=0;refusedSlots.clear();chooseNextStorage();
        logDirect("Fetching requested items from registered storage");
    }

    public void startResupply() {
        boolean mining=hivemind.getMineProcess().isActive(),debris=hivemind.getMineProcess().targetsAncientDebris();
        Map<String,Integer> targets=EmergencySupplies.targets(BotInventory.needsFood(ctx),HiveMind.settings().botMinimumFood.value,HiveMind.settings().botRestockFood.value,mining,BotInventory.needsBlocks(ctx),HiveMind.settings().botMinimumBlocks.value,HiveMind.settings().botRestockBlocks.value,BotInventory.slot(ctx,s->BotInventory.pickaxe(s,debris))<0,debris);
        if(targets.isEmpty())throw new IllegalStateException("No emergency supplies are missing");
        startFetch(targets,true);survivalSupply=true;
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
        if(stage==Stage.STOCK_BOX)return unpackStockBox();
        if(stage==Stage.PORTABLE)return portableFetch();
        if (calcFailed) {
            return stage == Stage.RETURN ? fail("no usable route to the mining site") : tryNextStorage("storage #" + storageId + " is unreachable");
        }
        if (++stageTicks > ((stage == Stage.TRAVEL || stage == Stage.RETURN) ? 12000 : 1800)) {
            return stage == Stage.RETURN ? fail("return trip timed out") : tryNextStorage("storage #" + storageId + " timed out");
        }
        String currentDimension = MiningRouteAccess.dimension(ctx);
        String requiredDimension = stage == Stage.RETURN ? returnDimension : depositDimension;
        if (!currentDimension.equals(requiredDimension)) {
            if (!HiveMind.settings().mineTravelBetweenDimensions.value) return fail("dimension travel is disabled");
            hivemind.getPortalTravelProcess().start(requiredDimension,stage==Stage.RETURN?returnTo:chest);
            return pause();
        }
        if (stage != Stage.RETURN && HiveMind.settings().mineAvoidSculk.value
                && hivemind.getSculkProcess().snapshot().contains(chest.getX(), chest.getY(), chest.getZ()))
            return tryNextStorage("storage #" + storageId + " is inside a known sculk hazard zone");
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
        if(!hivemind.getActions().allow(ActionArbiter.Channel.INVENTORY,ActionArbiter.Channel.HAND,ActionArbiter.Channel.LOOK,ActionArbiter.Channel.USE))return new PathingCommand(null,PathingCommandType.DEFER);
        if(!hivemind.getNetwork().team().storageAccess(new WorldRoutes.Endpoint(depositDimension,chest.getX(),chest.getY(),chest.getZ()),fetch?fetchTargets:Map.of()))return pause();
        hivemind.getInputOverrideHandler().clearAllKeys();
        ctx.playerController().resetBlockRemoving();
        if (stage == Stage.OPEN) {
            if(!ctx.world().hasChunkAt(chest))return stageTicks>timeoutTicks(40)?tryNextStorage("Storage chunk was not received at "+depositDimension+" "+chest.toShortString()):pause();
            var block=ctx.world().getBlockState(chest).getBlock();
            if(!supports(block))return stageTicks>timeoutTicks(40)?tryNextStorage("Expected storage at "+depositDimension+" "+chest.toShortString()+"; found "+BuiltInRegistries.BLOCK.getKey(block)):pause();
            if(!StorageAccessPolicy.permits(block==Blocks.ENDER_CHEST,resupply,HiveMind.settings().botUseEnderChests.value))return tryNextStorage("Ender chests are private withdrawal-only stock; enable botUseEnderChests for resupply");
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
                return stageTicks > timeoutTicks(100) ? fail("the server did not send the storage contents") : pause();
            }
            openedMenu = menu;
            transition(Stage.TRANSFER);
            return pause();
        }
        if (openRequested) {
            return stageTicks > timeoutTicks(100) ? tryNextStorage("storage #" + storageId + " could not be opened; it may be blocked or protected") : pause();
        }
        Optional<Rotation> rotation = RotationUtils.reachable(ctx, chest);
        if (rotation.isEmpty()) {
            return tryNextStorage("storage #" + storageId + " is not reachable from the arrival position");
        }
        hivemind.getLookBehavior().updateTarget(rotation.get(), true);
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
        if(pendingSlot<0&&contentRevision>openRevision)rememberStock(storageSlots);
        if (pendingSlot >= 0) {
            if (++pendingTicks < hivemind.getReliability().health().interactionTicks()) return pause();
            // Correct client prediction may produce no slot update at all. Reopening requests
            // authoritative contents, including the player's inventory, rather than guessing an acknowledgement.
            if (contentRevision <= pendingRevision || synchronizedMenuId != openedMenu.containerId) {
                closeOwnedMenu();
                openRequested = false;
                transition(Stage.OPEN);
                return pause();
            }
            rememberStock(storageSlots);
            ItemStack remaining = pendingWithdrawal ? ItemStack.EMPTY : ctx.player().getInventory().getItem(pendingSlot);
            int remainingCount = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = pendingWithdrawal ? Math.max(0, BotInventory.count(ctx,
                    stack -> ItemStack.isSameItemSameComponents(stack, pendingStack)) - pendingInventoryCount)
                    : DepositPolicy.transferred(pendingStack.getCount(), remainingCount);
            if (transferred == 0) {
                refusedSlots.add(pendingSlot);
            } else {
                hivemind.getReliability().success("storage:"+storageId,"Confirmed "+(pendingWithdrawal?"withdrawal":"deposit")+" of "+transferred+" "+BotItems.id(pendingStack));
                hivemind.getReliability().routes().success("storage",new WorldRoutes.Endpoint(depositDimension,chest.getX(),chest.getY(),chest.getZ()),System.currentTimeMillis());
                movedItems += transferred;
                hivemind.getOperations().journal().note("Transfer",(pendingWithdrawal?"Withdrew ":"Deposited ")+transferred+" "+BotItems.id(pendingStack)+" at storage #"+storageId);
                if (!pendingWithdrawal) hivemind.getMineProcess().recordDeposit(pendingStack, transferred);
                if (!remaining.isEmpty()) {
                    refusedSlots.add(pendingSlot); // A partial stack filled the remaining chest space.
                }
            }
            boolean borrowed=pendingWithdrawal&&fetchVariants&&ExpeditionLoadout.portable(pendingStack)&&transferred>0;
            pendingSlot = -1;
            pendingStack = null;
            pendingWithdrawal = false;
            if(borrowed){
                stockBoxes.confirmedWithdrawal(pendingBoxEmptySlots);closeOwnedMenu();openRequested=false;unpackStarted=false;
                transition(Stage.STOCK_BOX);logDirect("Collected a storage shulker; waiting for a safe spot to unpack supplies");
            }
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
        if(fetch&&!fetchComplete())return fail("Requested supplies were not found or could not be transferred from the accessible containers");
        hivemind.getReliability().completed("deposit",(resupply?"Requested reserves checked":"Requested inventory transfer confirmed")+"; "+movedItems+" items moved");
        closeOwnedMenu();
        if(stockBoxes.borrowed()>0)logDirect("Unpacked "+stockBoxes.borrowed()+" storage shulkers; "+(fetchComplete()?"requested reserves filled":"some requested supplies are still unavailable"));
        else if(usedPortable)logDirect("Refilled requested reserves from carried shulkers");
        else logDirect((resupply ? "Restocked " : "Deposited ") + movedItems + " items");
        if (returnTo != null && (resupply || hivemind.getMineProcess().isActive())) transition(Stage.RETURN);
        else onLostControl();
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** A quiet-period, placement or retry delay is not evidence that carried stock is unavailable. */
    private PathingCommand portableFetch(){
        var shulkers=hivemind.getShulkerProcess();long now=System.currentTimeMillis();
        if(shulkers.isActive())return new PathingCommand(null,PathingCommandType.DEFER);
        if(portableStarted){
            portableStarted=false;
            if(shulkers.hasPendingRecovery())return fail("Carried shulker recovery is still pending; keeping its checkpoint and remaining stock");
            var after=stockCounts();
            if(!portableRecovering&&after.entrySet().stream().noneMatch(e->e.getValue()>portableBefore.getOrDefault(e.getKey(),0)))
                return fail("Carried shulker yielded no confirmed supplies; check placement space and inventory room before retrying");
            portableRecovering=false;portableDeadline=now+120000;
        }
        if(fetchComplete()&&!shulkers.hasPendingRecovery())return finishTransfer();
        if(now>=portableDeadline)return fail("Could not safely open carried shulkers; supplies remain carried. Check clear ground, threats and inventory room");
        if(shulkers.hasPortableItems(fetchTargets,fetchVariants)||shulkers.hasPendingRecovery()){
            if(hivemind.getElytraProcess().isActive())return new PathingCommand(null,PathingCommandType.DEFER);
            var before=stockCounts();boolean recovery=shulkers.hasPendingRecovery();
            if(shulkers.requestItems(fetchTargets,fetchVariants)){
                portableBefore=before;portableRecovering=recovery;portableStarted=true;
                hivemind.getOperations().journal().note("Supply","Opening carried shulker before considering external storage");
                return new PathingCommand(null,PathingCommandType.DEFER);
            }
            return pause();
        }
        if(portableOnly){logDirect("Used available carried supplies; reassessing remaining reserves before any storage trip");onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
        // Only exhausted/unhelpful carried stock permits a storage trip for the remaining request.
        Map<String,Integer> remaining=Map.copyOf(fetchTargets);boolean variants=fetchVariants,emergency=survivalSupply;
        BlockPos origin=returnTo;String originDimension=returnDimension;
        onLostControl();
        try{startFetch(remaining,variants);returnTo=origin;returnDimension=originDimension;survivalSupply=emergency;}
        catch(IllegalStateException error){fetch=resupply=true;survivalSupply=emergency;return fail(error.getMessage());}
        return new PathingCommand(null,PathingCommandType.DEFER);
    }

    private Map<String,Integer> stockCounts(){Map<String,Integer> counts=new java.util.LinkedHashMap<>();fetchTargets.keySet().forEach(key->counts.put(key,fetchCount(key)));return counts;}
    private boolean fetchComplete(){return fetchTargets.entrySet().stream().allMatch(e->fetchCount(e.getKey())>=fetchGoal(e.getKey(),e.getValue()));}
    private int emptyInventorySlots(){int empty=0;for(int i=0;i<36;i++)if(ctx.player().getInventory().getItem(i).isEmpty())empty++;return empty;}
    private boolean usefulStockBox(ItemStack stack){
        if(!fetch||!fetchVariants||!ExpeditionLoadout.portable(stack))return false;
        var contents=stack.get(DataComponents.CONTAINER);if(contents==null)return false;
        var items=net.minecraft.core.NonNullList.<ItemStack>withSize(27,ItemStack.EMPTY);contents.copyInto(items);
        return items.stream().anyMatch(item->needsSupply(item));
    }
    private PathingCommand unpackStockBox(){
        if(hivemind.getShulkerProcess().isActive())return new PathingCommand(null,PathingCommandType.DEFER);
        if(unpackStarted){
            var result=stockBoxes.finishUnpack(stockCounts(),hivemind.getShulkerProcess().hasPendingRecovery());unpackStarted=false;
            if(result==StockBoxTrip.Result.RECOVERY_REQUIRED)return fail("The storage shulker still needs recovery; its checkpoint is retained. No further boxes were taken");
            if(result==StockBoxTrip.Result.NO_PROGRESS)return fail("Storage shulker yielded no confirmed supplies; the box was recovered. Check inventory space and server permissions");
            if(fetchComplete())return finishTransfer();
            openRequested=false;refusedSlots.clear();transition(Stage.TRAVEL);return pause();
        }
        if(++stageTicks>1200)return fail("Could not safely unpack the storage shulker; it remains in the inventory. Leave clear floor space and enable placement/breaking");
        var before=stockCounts();
        if(hivemind.getShulkerProcess().requestItems(fetchTargets,true)){stockBoxes.beginUnpack(before);unpackStarted=true;return new PathingCommand(null,PathingCommandType.DEFER);}
        return pause();
    }

    private boolean needsSupply(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (fetch) return fetchTargets.entrySet().stream().anyMatch(e->(fetchVariants?ExpeditionLoadout.matches(stack,e.getKey()):BotItems.ordinary(stack)&&BotItems.id(stack).equals(e.getKey()))&&fetchCount(e.getKey())<fetchGoal(e.getKey(),e.getValue()));
        if (wantFood && BotInventory.food(stack)) return BotInventory.count(ctx, BotInventory::food)
                < Math.max(SurvivalPolicy.bounded(HiveMind.settings().botMinimumFood.value, 1, 64),
                        SurvivalPolicy.bounded(HiveMind.settings().botRestockFood.value, 1, 128));
        if (wantBlocks && BotInventory.building(stack)) return BotInventory.count(ctx, BotInventory::building)
                < Math.max(SurvivalPolicy.bounded(HiveMind.settings().botMinimumBlocks.value, 0, 128),
                        SurvivalPolicy.bounded(HiveMind.settings().botRestockBlocks.value, 0, 256));
        return wantPickaxe && BotInventory.pickaxe(stack, hivemind.getMineProcess().targetsAncientDebris())
                && BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, hivemind.getMineProcess().targetsAncientDebris())) < 0;
    }

    private PathingCommand withdraw(int storageSlots) {
        int slot = -1;
        for (int i = 0; i < storageSlots; i++) if (!refusedSlots.contains(i) && needsSupply(openedMenu.slots.get(i).getItem())
                && openedMenu.slots.get(i).mayPickup(ctx.player())) { slot = i; break; }
        if(slot<0&&fetch&&fetchVariants&&!fetchComplete()){
            for(int i=0;i<storageSlots;i++)if(!refusedSlots.contains(i)&&openedMenu.slots.get(i).mayPickup(ctx.player())&&usefulStockBox(openedMenu.slots.get(i).getItem())){slot=i;break;}
            if(slot>=0){
                if(hivemind.getShulkerProcess().hasPendingRecovery())return fail("Recover the outstanding portable shulker before taking another box from storage");
                pendingBoxEmptySlots=emptyInventorySlots();
                if(!stockBoxes.canBorrow(pendingBoxEmptySlots))return fail(stockBoxes.borrowed()>=8?"Eight storage boxes were tried this trip; refill again after checking stock":"Free at least two main-inventory slots before collecting a storage shulker");
            }
        }
        if (slot < 0) {
            if (fetch) {
                if (fetchTargets.entrySet().stream().allMatch(entry->fetchCount(entry.getKey())>=fetchGoal(entry.getKey(),entry.getValue())) || remainingChests.isEmpty()) return finishTransfer();
                return tryNextStorage("Checking the next storage for requested items");
            }
            boolean enough = (!wantFood || !BotInventory.needsFood(ctx)) && (!wantBlocks || !BotInventory.needsBlocks(ctx))
                    && (!wantPickaxe || BotInventory.slot(ctx, item -> BotInventory.pickaxe(item, hivemind.getMineProcess().targetsAncientDebris())) >= 0);
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
        BlockOptionalMetaLookup outputs = miningOutputs == null ? hivemind.getMineProcess().depositTargets() : miningOutputs;
        Set<Integer> buildingSlots = new HashSet<>(MiningWasteCleanup.reservedBuildingSlots(ctx, outputs));
        if (HiveMind.settings().botSurvival.value) buildingSlots.addAll(BotInventory.buildingReserves(ctx, outputs));
        for (int i = 0; i < 36; i++) {
            ItemStack stack = ctx.player().getInventory().getItem(i);
            if (stack.isEmpty() || (skipRefused && refusedSlots.contains(i))) {
                continue;
            }
            if (product != null) {
                if (BotItems.id(stack).equals(product) && BotItems.expeditionSupply(stack) && accepts(rule,stack)) return Optional.of(i);
                continue;
            }
            if (hivemind.getCompanionProcess().reserves(BotItems.id(stack))) continue;
            if (!accepts(rule, stack)) continue;
            boolean supply = stack.isDamageableItem() || stack.has(DataComponents.FOOD) || stack.has(DataComponents.EQUIPPABLE) || ExpeditionLoadout.portable(stack) || stack.is(net.minecraft.world.item.Items.FIREWORK_ROCKET) || stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
            if (!depositAll && buildingSlots.contains(i)) continue;
            if (!DepositPolicy.retain(i, depositAll, HiveMind.settings().depositKeepHotbar.value,
                    HiveMind.settings().depositKeepSupplies.value, supply,
                    HiveMind.settings().depositKeepItems.value.contains(stack.getItem()), outputs != null && outputs.has(stack))) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    public static boolean accepts(StorageRule rule, ItemStack stack) {
        return rule.accepts(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getItem() instanceof BlockItem,
                HiveMind.settings().mineWasteItems.value.contains(stack.getItem()),
                stack.isDamageableItem() || stack.has(DataComponents.FOOD) || stack.has(DataComponents.EQUIPPABLE) || BotInventory.building(stack)
                        || ExpeditionLoadout.portable(stack) || stack.is(net.minecraft.world.item.Items.FIREWORK_ROCKET) || stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)
                        || HiveMind.settings().depositKeepItems.value.contains(stack.getItem()));
    }

    private PathingCommand fail(String reason) {
        if(ctx.player()!=null&&ctx.world()!=null)hivemind.getOperations().journal().note("Storage failure",reason);
        if (fetch) { boolean emergency=survivalSupply;onLostControl(); lastFetchError=reason;if(emergency)hivemind.getSurvivalProcess().supplyFailed(reason); logDirect("Fetch stopped: "+reason); return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL); }
        if (hivemind.getJobManager() != null) hivemind.getJobManager().fail("Deposit: " + reason);
        if (resupply && hivemind.getSurvivalProcess() != null) hivemind.getSurvivalProcess().supplyFailed(reason);
        if (!resupply && hivemind.getMineProcess() != null) {
            hivemind.getMineProcess().cancel();
        }
        onLostControl();
        lastDepositError = reason;
        logDirect("Storage trip stopped: " + reason + ". Bot work requires attention; no items were dropped.");
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private boolean chooseNextStorage() {
        hivemind.getNetwork().team().releaseStorage();
        String dimension = MiningRouteAccess.dimension(ctx);
        WorldRoutes.Storage next = remainingChests.stream().filter(storage -> resupply || firstTransferable(false, storage.rule()).isPresent()).min(Comparator
                .comparingDouble((WorldRoutes.Storage storage)->hivemind.getReliability().routes().penalty("storage",storage.endpoint(),System.currentTimeMillis()))
                .thenComparingInt((WorldRoutes.Storage storage) -> resupply ? (storage.rule().kind() == StorageRule.Kind.SUPPLIES ? 0 : 1) : (storage.rule().kind() == StorageRule.Kind.ANY ? 1 : 0))
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
        if(chest!=null){hivemind.getReliability().routes().failed("storage",new WorldRoutes.Endpoint(depositDimension,chest.getX(),chest.getY(),chest.getZ()),reason,System.currentTimeMillis());hivemind.getReliability().failed("storage:"+storageId,reason,movedItems);}
        suspendForConflict();
        if (!chooseNextStorage()) { if(fetch)return finishTransfer(); return fail(reason + "; all saved storage was tried (" + movedItems + " items deposited)"); }
        logDirect(reason + "; trying storage #" + storageId);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Close our own menu before retreating, preserving the trip and any already-confirmed transfer credit. */
    public void suspendForConflict() {
        hivemind.getNetwork().team().releaseStorage();
        if (!pendingWithdrawal && pendingSlot >= 0 && pendingStack != null && openedMenu != null && contentRevision > pendingRevision
                && synchronizedMenuId == openedMenu.containerId) {
            ItemStack remaining = ctx.player().getInventory().getItem(pendingSlot);
            int count = remaining.getItem() == pendingStack.getItem() ? remaining.getCount() : 0;
            int transferred = DepositPolicy.transferred(pendingStack.getCount(), count);
            movedItems += transferred;
            hivemind.getMineProcess().recordDeposit(pendingStack, transferred);
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
        stockBoxes.reset();unpackStarted=false;pendingBoxEmptySlots=0;
        portableStarted=portableRecovering=portableOnly=usedPortable=false;portableDeadline=0;portableBefore=Map.of();
        pendingSlot = -1;
        pendingStack = null;
        synchronizedMenuId = -1;
        openRequested = false;
        depositAll = false;
        survivalSupply = resupply = fetch = wantFood = wantBlocks = wantPickaxe = pendingWithdrawal = false;
        fetchTargets = Map.of(); fetchVariants=false; product = null;
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
    private int timeoutTicks(int normal){return (int)(hivemind.getReliability().health().timeout(normal*50L)/50);}

}

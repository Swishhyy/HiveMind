/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.process;

import hivemind.HiveMind;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.BlockChangeEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.api.pathing.goals.GoalNear;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.RotationUtils;
import hivemind.api.utils.input.Input;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.mining.ConstructionPolicy;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.social.BlastPolicy;
import hivemind.process.elytra.ElytraBehavior;
import hivemind.pathing.movement.CalculationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Owned portable resupply: confirm placement, open, withdraw, close, mine and collect the same box. */
public final class ShulkerSupplyProcess extends HiveMindProcessHelper implements IBehavior {
    private enum Stage { IDLE, PLACE, WAIT_PLACE, OPEN, TRANSFER, BREAK, PICKUP }
    private record Need(Predicate<ItemStack> matches,int count,String label){}
    private Stage stage=Stage.IDLE;
    private Path root;
    private Object world;
    private PortableBoxStore store;
    private BlockPos box;
    private ItemStack boxItem=ItemStack.EMPTY;
    private List<Need> needs=List.of();
    private int source=-1,previous=-1,ticks,moved,pending=-1,pendingCount,waitTicks,beforeBoxes;
    private long revision,openRevision,pendingRevision,retryAt,nextScan;
    private int synchronizedMenu=-1;
    private boolean openRequested,confirmed,placementConfirmed,recoveryOnly;
    private AbstractContainerMenu menu;
    private final Set<Integer> refused=new HashSet<>();
    public ShulkerSupplyProcess(HiveMind bot){super(bot);}
    @Override public boolean isActive(){return stage!=Stage.IDLE;}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -.15;}
    private void load(){
        Path here=MiningRouteAccess.root(ctx);
        if(here.equals(root)&&world==ctx.world())return;
        onLostControl();root=here;world=ctx.world();store=new PortableBoxStore(here);
    }
    private static boolean shulker(ItemStack stack){return stack.getItem() instanceof BlockItem item&&item.getBlock() instanceof ShulkerBoxBlock;}
    private int count(Predicate<ItemStack> filter){return BotInventory.count(ctx,filter);}
    private boolean allowed(){return HiveMind.settings().botUseShulkers.value&&HiveMind.settings().allowInventory.value&&HiveMind.settings().allowPlace.value&&HiveMind.settings().allowBreak.value
            &&ctx.player()!=null&&ctx.world()!=null&&ctx.player().isAlive()&&!hivemind.getAutonomy().paused()&&ctx.minecraft().gameMode!=null;}
    private boolean safeSite(){
        if(!ctx.player().onGround()||ctx.player().isFallFlying()||ctx.player().isInWater()||ctx.player().isInLava()||hivemind.getClutch().active()
                ||hivemind.getConflictProcess().isActive()||hivemind.getSculkProcess().isActive()||hivemind.getElytraProcess().isActive()||hivemind.getStalkProcess().committedCombat())return false;
        return ctx.entitiesStream().noneMatch(entity->entity!=ctx.player()&&entity.isAlive()&&(
                entity instanceof net.minecraft.world.entity.player.Player player&&!player.isSpectator()&&!hivemind.getNetwork().isTeammate(player.getUUID())&&!hivemind.getBotControl().isTrusted(player.getUUID())&&player.distanceToSqr(ctx.player())<16*16
                ||entity instanceof Mob mob&&mob.getType().getCategory()==MobCategory.MONSTER&&mob.distanceToSqr(ctx.player())<8*8));
    }
    private List<Need> loadout(){
        List<Need> wanted=new ArrayList<>();
        if(HiveMind.settings().botAutoTotem.value)wanted.add(new Need(s->s.is(Items.TOTEM_OF_UNDYING),2,"totems"));
        wanted.add(new Need(BotInventory::food,Math.max(4,HiveMind.settings().botMinimumFood.value),"food"));
        if(HiveMind.settings().botUseElytra.value){wanted.add(new Need(ElytraBehavior::isFireworks,Math.max(16,Math.max(HiveMind.settings().botFlightRocketReserve.value,HiveMind.settings().elytraMinFireworksBeforeLanding.value)+4),"rockets"));
            ItemStack worn=ctx.player().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            int minimum=Math.max(32,HiveMind.settings().elytraMinimumDurability.value+1);
            if(!worn.is(Items.ELYTRA)||worn.getMaxDamage()-worn.getDamageValue()<minimum)wanted.add(new Need(s->s.is(Items.ELYTRA)&&s.getMaxDamage()-s.getDamageValue()>=minimum,1,"elytra"));}
        if(HiveMind.settings().allowWaterBucketFall.value&&ctx.world().dimension()!=net.minecraft.world.level.Level.NETHER)wanted.add(new Need(s->s.is(Items.WATER_BUCKET),1,"water bucket"));
        if(hivemind.getMineProcess().isActive()){
            wanted.add(new Need(s->BotInventory.pickaxe(s,hivemind.getMineProcess().targetsAncientDebris()),1,"pickaxe"));
            wanted.add(new Need(BotInventory::building,Math.max(32,HiveMind.settings().botMinimumBlocks.value),"building blocks"));
        }
        if(hivemind.getStalkProcess().hunting()){
            wanted.add(new Need(s->(s.is(ItemTags.SWORDS)||s.is(ItemTags.AXES))&&(!s.isDamageableItem()||s.getMaxDamage()-s.getDamageValue()>Math.max(1,HiveMind.settings().botToolReserve.value)),1,"weapon"));
            String style=hivemind.getStalkProcess().combatStyle();
            if(!style.equals("melee")){
                if(!style.equals("anchor")){wanted.add(new Need(s->s.is(Items.END_CRYSTAL),16,"crystals"));wanted.add(new Need(s->s.is(Items.OBSIDIAN),16,"obsidian"));}
                if(!style.equals("crystal")&&BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx))){wanted.add(new Need(s->s.is(Items.RESPAWN_ANCHOR),8,"anchors"));wanted.add(new Need(s->s.is(Items.GLOWSTONE),16,"glowstone"));}
            }
        }
        return wanted;
    }
    public boolean requestItems(Map<String,Integer> targets){return requestItems(targets,false);}
    public boolean requestItems(Map<String,Integer> targets,boolean variants){
        return request(targets.entrySet().stream().map(entry->new Need(s->(variants?BotItems.expeditionSupply(s):BotItems.ordinary(s))&&BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(entry.getKey()),entry.getValue(),entry.getKey())).toList());
    }
    public boolean requestSupplies(){return ctx.player()!=null&&request(loadout());}
    private boolean request(List<Need> wanted){
        if(!allowed()||System.currentTimeMillis()<retryAt)return false;
        load();if(isActive())return true;
        if(!safeSite()||ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        var recovery=store.box();
        if(recovery!=null){
            if(!recovery.point().dimension().equals(MiningRouteAccess.dimension(ctx)))return false;
            box=MiningRouteAccess.position(recovery.point());
            if(box.distSqr(ctx.playerFeet())>64*64||!ctx.world().hasChunkAt(box))return false;
            var block=BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(box).getBlock()).toString();
            if(!recovery.confirmed()){logDirect("Unconfirmed shulker placement at "+box.toShortString()+"; leaving it for manual recovery");retryAt=System.currentTimeMillis()+60000;return false;}
            boxItem=new ItemStack(BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(recovery.item())));
            confirmed=recoveryOnly=true;needs=List.of();beforeBoxes=count(s->s.is(boxItem.getItem()));moved=0;previous=ctx.player().getInventory().getSelectedSlot();
            transition(block.equals(recovery.item())?Stage.BREAK:Stage.PICKUP);return true;
        }
        needs=wanted.stream().filter(need->count(need.matches())<need.count()).toList();if(needs.isEmpty())return false;
        for(int slot=0;slot<36;slot++){
            ItemStack stack=ctx.player().getInventory().getItem(slot);if(!shulker(stack))continue;
            var contents=stack.get(DataComponents.CONTAINER);if(contents==null)continue;
            var stacks=net.minecraft.core.NonNullList.<ItemStack>withSize(27,ItemStack.EMPTY);contents.copyInto(stacks);
            boolean useful=stacks.stream().anyMatch(item->needs.stream().anyMatch(need->need.matches().test(item)&&count(need.matches())<need.count()));
            if(!useful)continue;
            BlockPos point=placement();if(point==null)return false;
            source=slot;boxItem=stack.copy();box=point;previous=ctx.player().getInventory().getSelectedSlot();beforeBoxes=count(s->s.is(boxItem.getItem()));moved=0;confirmed=recoveryOnly=false;
            transition(Stage.PLACE);return true;
        }
        return false;
    }
    private BlockPos placement(){
        BlockPos feet=ctx.playerFeet();
        for(int radius=2;radius<=3;radius++)for(int y=-1;y<=0;y++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++){
            if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;BlockPos pos=feet.offset(x,y,z);var support=ctx.world().getBlockState(pos.below());
            String supportId=BuiltInRegistries.BLOCK.getKey(support.getBlock()).getPath();
            if(!ctx.world().hasChunkAt(pos)||!ctx.world().getWorldBorder().isWithinBounds(pos)||!ctx.world().getBlockState(pos).isAir()||!ctx.world().getBlockState(pos.above()).isAir()
                    ||!support.isCollisionShapeFullBlock(ctx.world(),pos.below())||ConstructionPolicy.isContainer(BuiltInRegistries.BLOCK.getKey(support.getBlock()).getPath())
                    ||supportId.endsWith("_table")||Set.of("beehive","bee_nest","note_block","jukebox","composter").contains(supportId)
                    ||!ctx.world().noCollision(new AABB(pos))||new CalculationContext(hivemind).isPossiblyProtected(pos.getX(),pos.getY(),pos.getZ()))continue;
            var hit=ctx.world().clip(new net.minecraft.world.level.ClipContext(ctx.playerHead(),new Vec3(pos.getX()+.5,pos.getY()-.001,pos.getZ()+.5),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,ctx.player()));
            if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(pos.below())&&hit.getLocation().distanceToSqr(ctx.playerHead())<=Math.pow(ctx.playerController().getBlockReachDistance(),2))return pos.immutable();
        }
        return null;
    }
    private boolean sameBlock(){return BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(box).getBlock()).toString().equals(BuiltInRegistries.ITEM.getKey(boxItem.getItem()).toString());}
    private void save(boolean owned){store.save(new PortableBoxStore.Box(MiningRouteAccess.endpoint(ctx,box),BuiltInRegistries.ITEM.getKey(boxItem.getItem()).toString(),owned));}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        if(!allowed()||!root.equals(MiningRouteAccess.root(ctx))||world!=ctx.world()){onLostControl();return null;}
        if(ctx.minecraft().gui.screen()!=null&&ctx.player().containerMenu==ctx.player().inventoryMenu){
            hivemind.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,false);ctx.playerController().resetBlockRemoving();return pause();
        }
        if(!safe)return pause();
        if(failed||++ticks>600)return fail("Could not complete portable resupply; recovery point retained");
        if(hivemind.getConflictProcess().isActive()||hivemind.getStalkProcess().committedCombat()){closeMenu();return new PathingCommand(null,PathingCommandType.DEFER);}
        if(box==null)return fail("Missing portable box position");
        if(box.distSqr(ctx.playerFeet())>12){return new PathingCommand(new GoalNear(box,2),PathingCommandType.REVALIDATE_GOAL_AND_PATH);}
        hivemind.getInputOverrideHandler().clearAllKeys();if(stage!=Stage.BREAK)ctx.playerController().resetBlockRemoving();
        switch(stage){
            case PLACE -> {
                if(!ctx.world().getBlockState(box).isAir())return fail("Placement space changed");
                if(source>=9){ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,8,ContainerInput.SWAP,ctx.player());source=8;return pause();}
                if(!shulker(ctx.player().getInventory().getItem(source)))return fail("Shulker inventory slot changed");
                ctx.player().getInventory().setSelectedSlot(source);ctx.playerController().syncHeldItem();
                var rotation=RotationUtils.reachable(ctx,box.below());if(rotation.isEmpty())return fail("Cannot reach shulker placement face");
                hivemind.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),new Vec3(box.getX()+.5,box.getY()-.001,box.getZ()+.5),ctx.playerRotations()),true);
                if(ctx.isLookingAt(box.below())){save(false);ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,new BlockHitResult(new Vec3(box.getX()+.5,box.getY(),box.getZ()+.5),Direction.UP,box.below(),false));transition(Stage.WAIT_PLACE);}
            }
            case WAIT_PLACE -> {
                if(placementConfirmed&&sameBlock()&&count(s->s.is(boxItem.getItem()))<beforeBoxes){confirmed=true;save(true);beforeBoxes=count(s->s.is(boxItem.getItem()));transition(Stage.OPEN);}
                else if(ticks>60){if(ctx.world().getBlockState(box).isAir())store.save(null);return fail("Server did not confirm shulker placement");}
            }
            case OPEN -> {
                var current=ctx.player().containerMenu;
                if(current!=ctx.player().inventoryMenu){
                    if(!openRequested||!(current instanceof ShulkerBoxMenu))return fail("Unexpected container opened");
                    menu=current;
                    if(synchronizedMenu==current.containerId&&revision>openRevision){transition(Stage.TRANSFER);return pause();}
                    if(ticks>100)return fail("No server contents for the shulker");return pause();
                }
                if(openRequested){if(ticks>60)return fail("Shulker could not be opened");return pause();}
                var aim=RotationUtils.reachable(ctx,box);if(aim.isEmpty())return fail("Shulker is out of reach");hivemind.getLookBehavior().updateTarget(aim.get(),true);
                if(ctx.isLookingAt(box)){openRequested=true;openRevision=revision;ticks=0;ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(box),Direction.UP,box,false));}
            }
            case TRANSFER -> {return transfer();}
            case BREAK -> {
                if(!sameBlock()) {transition(Stage.PICKUP);return pause();}
                if(!HiveMind.settings().allowBreak.value)return fail("Enable allowBreak to recover the placed shulker");
                if(!PortableSupplyPolicy.mayRecover(confirmed,sameBlock(),ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty()))return fail("Shulker recovery ownership changed");
                int pick=BotInventory.slot(ctx,s->BotInventory.pickaxe(s,false));
                if(pick>=9){ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,pick,8,ContainerInput.SWAP,ctx.player());return pause();}
                if(pick>=0){ctx.player().getInventory().setSelectedSlot(pick);ctx.playerController().syncHeldItem();}
                var aim=RotationUtils.reachable(ctx,box);if(aim.isEmpty())return fail("Cannot reach placed shulker");hivemind.getLookBehavior().updateTarget(aim.get(),true);
                if(ctx.isLookingAt(box))hivemind.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,true);
            }
            case PICKUP -> {
                if(count(s->s.is(boxItem.getItem()))>beforeBoxes){store.save(null);boolean supplied=moved>0,recovered=recoveryOnly;onLostControl();retryAt=System.currentTimeMillis()+(supplied||recovered?0:30000);logDirect("Recovered portable shulker"+(supplied?" after resupplying":""));return null;}
                ItemEntity dropped=ctx.entitiesStream().filter(e->e instanceof ItemEntity item&&shulker(item.getItem())&&item.getItem().is(boxItem.getItem())&&item.distanceToSqr(Vec3.atCenterOf(box))<16).map(e->(ItemEntity)e).findFirst().orElse(null);
                if(dropped!=null)return new PathingCommand(new GoalNear(dropped.blockPosition(),0),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
                return new PathingCommand(new GoalNear(box,0),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            }
            default -> {}
        }
        return pause();
    }
    private PathingCommand transfer(){
        if(menu==null||ctx.player().containerMenu!=menu||!(menu instanceof ShulkerBoxMenu)||menu.slots.size()!=63)return fail("Shulker menu changed during resupply");
        if(!menu.getCarried().isEmpty())return fail("Clear the inventory cursor before shulker resupply");
        if(pending>=0){
            if(++waitTicks<5)return pause();
            if(revision<=pendingRevision||synchronizedMenu!=menu.containerId){closeMenu();openRequested=false;transition(Stage.OPEN);return pause();}
            int remaining=count(needs.get(pending).matches());if(remaining<=pendingCount)refused.add(pending);else moved+=remaining-pendingCount;
            pending=-1;return pause();
        }
        for(int n=0;n<needs.size();n++){
            Need need=needs.get(n);if(refused.contains(n)||count(need.matches())>=need.count())continue;
            for(int slot=0;slot<27;slot++){
                ItemStack stack=menu.slots.get(slot).getItem();if(stack.isEmpty()||!need.matches().test(stack))continue;
                int empty=0,merge=0;
                for(int i=0;i<36;i++){ItemStack held=ctx.player().getInventory().getItem(i);if(held.isEmpty())empty++;else if(ItemStack.isSameItemSameComponents(stack,held))merge+=Math.max(0,held.getMaxStackSize()-held.getCount());}
                if(!PortableSupplyPolicy.canWithdraw(stack.getCount(),merge,empty,stack.getMaxStackSize()))continue;
                pending=n;pendingCount=count(need.matches());pendingRevision=revision;waitTicks=0;
                ctx.playerController().windowClick(menu.containerId,slot,0,ContainerInput.QUICK_MOVE,ctx.player());return pause();
            }
        }
        closeMenu();transition(Stage.BREAK);return pause();
    }
    public void onContents(int id){if(ctx.player()!=null&&id==ctx.player().containerMenu.containerId){synchronizedMenu=id;revision++;}}
    @Override public void onBlockChange(BlockChangeEvent event){if(stage==Stage.WAIT_PLACE&&box!=null&&event.getBlocks().stream().anyMatch(change->change.first().equals(box)))placementConfirmed=true;}
    public boolean ownsForRecovery(BlockPos pos){return stage==Stage.BREAK&&confirmed&&box!=null&&box.equals(pos)&&world==ctx.world()&&sameBlock();}
    private void transition(Stage next){stage=next;ticks=0;}
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    private PathingCommand fail(String reason){logDirect(reason+(box==null?"":" at "+box.toShortString()));onLostControl();retryAt=System.currentTimeMillis()+60000;return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
    private void closeMenu(){if(menu!=null&&ctx.player()!=null&&ctx.player().containerMenu==menu&&menu.getCarried().isEmpty())ctx.player().closeContainer();menu=null;}
    @Override public void onLostControl(){
        closeMenu();if(ctx.player()!=null){hivemind.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,false);ctx.playerController().resetBlockRemoving();
            if(previous>=0&&ctx.minecraft().gameMode!=null){ctx.player().getInventory().setSelectedSlot(previous);ctx.playerController().syncHeldItem();}}
        previous=source=pending=-1;stage=Stage.IDLE;box=null;boxItem=ItemStack.EMPTY;needs=List.of();refused.clear();openRequested=confirmed=placementConfirmed=recoveryOnly=false;revision=openRevision=pendingRevision=0;synchronizedMenu=-1;ticks=moved=0;
    }
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()){onLostControl();return;}
        if(isActive()&&world!=ctx.world()){onLostControl();return;}
        if(isActive()&&hivemind.getConflictProcess().isActive()){closeMenu();hivemind.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT,false);if(confirmed)transition(Stage.BREAK);return;}
        if(!isActive()&&allowed()&&BotWork.active(hivemind)&&System.currentTimeMillis()>=nextScan){nextScan=System.currentTimeMillis()+1000;requestSupplies();}
    }
    @Override public void onPlayerDeath(){onLostControl();}
    @Override public String displayName0(){return "Portable shulker "+stage;}
}

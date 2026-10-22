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
import hivemind.api.pathing.goals.GoalGetToBlock;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.RotationUtils;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.BotItems;
import hivemind.utils.bot.GatherPlanner;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.*;

/** Sequential real menu clicks; recipe results and reopened inventory contents are confirmed by the server. */
public final class WorkstationProcess extends HiveMindProcessHelper {
    private enum Stage{IDLE,TRAVEL,OPEN,PREPARE,FILL,WAIT,VERIFY}
    private record Fill(int slot,String item,int amount){}
    private Stage stage=Stage.IDLE;
    private GatherPlanner.Action action;
    private WorldRoutes.Endpoint station;
    private Path root;
    private AbstractContainerMenu menu;
    private final Deque<Fill> fills=new ArrayDeque<>();
    private boolean openRequested,ownsCursor,touched,pendingTake;
    private int sourceSlot=-1,ticks,delay,startCount,beforeTake,batchGoal,lastOutput,crafts;
    private long revision,openRevision,lastProgress,deadline;
    private int synchronizedMenu=-1;
    private String error;
    public WorkstationProcess(HiveMind bot){super(bot);}
    @Override public boolean isActive(){return stage!=Stage.IDLE;}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -0.45;}
    public String error(){return error;}
    public void onContents(int id){if(ctx.player()!=null&&id==ctx.player().containerMenu.containerId){synchronizedMenu=id;revision++;}}
    public void start(GatherPlanner.Action action){
        if(isActive())throw new IllegalStateException("A workstation operation is already active");
        var saved=MiningRouteAccess.locations(ctx).station(action.method()==GatherPlanner.Method.SMELT?"furnace":"crafting");
        if(saved==null)throw new IllegalStateException("Register the "+(action.method()==GatherPlanner.Method.SMELT?"furnace":"crafting table")+" with station first");
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("Close containers and clear the cursor before crafting");
        this.action=action;station=saved;root=MiningRouteAccess.root(ctx);startCount=BotItems.count(ctx,action.item());
        deadline=System.currentTimeMillis()+Math.max(60000L,Math.min(3600000L,HiveMind.settings().companionTaskTimeoutMS.value));
        error=null;touched=pendingTake=ownsCursor=openRequested=false;crafts=delay=0;transition(Stage.TRAVEL);
    }
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        try{return tick(failed,safe);}catch(RuntimeException e){return fail(e.getMessage());}
    }
    private PathingCommand tick(boolean failed,boolean safe){
        if(ctx.player()==null||ctx.world()==null||!root.equals(MiningRouteAccess.root(ctx)))return fail("World changed during crafting");
        if(failed||System.currentTimeMillis()>deadline)return fail("Workstation route failed or timed out");
        if(!MiningRouteAccess.dimension(ctx).equals(station.dimension())){hivemind.getPortalTravelProcess().start(station.dimension());return pause();}
        BlockPos pos=MiningRouteAccess.position(station);
        if(ctx.world().hasChunkAt(pos)&&!ctx.world().getBlockState(pos).is(action.method()==GatherPlanner.Method.SMELT?Blocks.FURNACE:Blocks.CRAFTING_TABLE))return fail("The registered workstation is missing or changed");
        if(stage==Stage.TRAVEL){var goal=new GoalGetToBlock(pos);if(!goal.isInGoal(ctx.playerFeet()))return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);if(safe)transition(Stage.OPEN);return pause();}
        if(!safe)return pause();
        hivemind.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();ticks++;
        if(stage==Stage.OPEN)return open(pos);
        if(ctx.player().containerMenu!=menu)return fail("The workstation was closed unexpectedly");
        if(!ownsCursor&&!menu.getCarried().isEmpty())return fail("Clear the cursor before continuing");
        if(delay-->0)return pause();
        if(stage==Stage.VERIFY){closeMenu();openRequested=false;transition(Stage.OPEN);return pause();}
        if(stage==Stage.PREPARE)return prepare();
        if(stage==Stage.FILL)return fill();
        if(stage==Stage.WAIT)return waitForResult();
        return pause();
    }
    private PathingCommand open(BlockPos pos){
        var current=ctx.player().containerMenu;
        if(current!=ctx.player().inventoryMenu){
            boolean supported=action.method()==GatherPlanner.Method.SMELT?current instanceof AbstractFurnaceMenu:current instanceof CraftingMenu;
            if(!openRequested||!supported)return fail("Unexpected workstation menu");
            if(synchronizedMenu!=current.containerId||revision<=openRevision)return ticks>100?fail("Server did not send workstation contents"):pause();
            menu=current;openRequested=false;
            if(pendingTake){
                int received=BotItems.count(ctx,action.item())-beforeTake;
                if(received<=0)return fail("The server did not confirm the crafted output; check recipe changes and inventory space");
                pendingTake=false;
                if(BotItems.count(ctx,action.item())>=startCount+action.amount())return finish();
            }
            transition(Stage.PREPARE);return pause();
        }
        if(openRequested)return ticks>100?fail("The workstation is blocked or protected"):pause();
        var rotation=RotationUtils.reachable(ctx,pos);if(rotation.isEmpty())return fail("Workstation is not reachable from the arrival position");
        hivemind.getLookBehavior().updateTarget(rotation.get(),true);
        if(ctx.isLookingAt(pos)){openRequested=true;openRevision=revision;ticks=0;ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
        return pause();
    }
    private PathingCommand prepare(){
        if(!menu.getCarried().isEmpty())return fail("Clear the workstation cursor");
        if(ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).noneMatch(ItemStack::isEmpty))return fail("Keep an empty inventory slot for workstation output");
        fills.clear();
        if(action.method()==GatherPlanner.Method.CRAFT){
            for(int i=1;i<=9;i++)if(!menu.slots.get(i).getItem().isEmpty())return fail("Crafting grid contains items; clear it before continuing");
            if(!menu.slots.get(0).getItem().isEmpty())return fail("Unexpected result in the crafting grid");
            for(int i=0;i<action.grid().size();i++)if(!action.grid().get(i).isEmpty())fills.add(new Fill(i+1,action.grid().get(i),1));
            if(++crafts>Math.max(1,Math.min(4096,HiveMind.settings().gatherMaxActions.value)))return fail("Crafting action limit reached");
        }else{
            ItemStack input=menu.slots.get(0).getItem(),output=menu.slots.get(2).getItem();
            if(!touched&&(!input.isEmpty()||!output.isEmpty()))return fail("Registered furnace is busy; clear its input and output before starting");
            if(!input.isEmpty()&&!BotItems.id(input).equals(action.grid().get(0)))return fail("Furnace input was replaced");
            if(!output.isEmpty()&&!BotItems.id(output).equals(action.item()))return fail("Furnace result was replaced");
            if(touched&&(!input.isEmpty()||!output.isEmpty())){lastOutput=output.getCount();lastProgress=System.currentTimeMillis();transition(Stage.WAIT);return pause();}
            int units=Math.min(64,(startCount+action.amount()-BotItems.count(ctx,action.item())+action.recipe().count()-1)/action.recipe().count());
            batchGoal=BotItems.count(ctx,action.item())+units*action.recipe().count();
            fills.add(new Fill(0,action.grid().get(0),units));
            var fuel=menu.slots.get(1).getItem();if(!fuel.isEmpty()&&!BotItems.id(fuel).equals(action.fuel()))return fail("Clear incompatible furnace fuel before continuing");
            fills.add(new Fill(1,action.fuel(),(units+7)/8));
        }
        touched=true;transition(Stage.FILL);return pause();
    }
    private PathingCommand fill(){
        if(ticks>1200)return fail("Workstation input transfer timed out");
        if(fills.isEmpty()){
            if(ownsCursor){restoreCursor();return pause();}
            lastProgress=System.currentTimeMillis();lastOutput=0;transition(Stage.WAIT);return pause();
        }
        Fill fill=fills.peekFirst();ItemStack target=menu.slots.get(fill.slot()).getItem();
        if(!target.isEmpty()&&!BotItems.id(target).equals(fill.item()))return fail("A workstation ingredient was replaced");
        int missing=fill.amount()-target.getCount();
        if(missing<=0){if(ownsCursor){restoreCursor();return pause();}fills.removeFirst();return pause();}
        ItemStack carried=menu.getCarried();
        if(ownsCursor){
            if(carried.isEmpty()){ownsCursor=false;sourceSlot=-1;return pause();}
            if(!BotItems.id(carried).equals(fill.item()))return fail("Workstation cursor changed unexpectedly");
            click(fill.slot(),carried.getCount()<=missing?0:1,ContainerInput.PICKUP);return pause();
        }
        int inventory=BotItems.slot(ctx,fill.item());if(inventory<0)return fail("Missing ingredient "+fill.item());
        sourceSlot=BotItems.menuSlot(inventory,menu.slots.size());ownsCursor=true;click(sourceSlot,0,ContainerInput.PICKUP);return pause();
    }
    private PathingCommand waitForResult(){
        int resultSlot=action.method()==GatherPlanner.Method.SMELT?2:0;ItemStack output=menu.slots.get(resultSlot).getItem();
        if(output.getCount()!=lastOutput){lastOutput=output.getCount();lastProgress=System.currentTimeMillis();}
        if(!output.isEmpty()&&(!BotItems.id(output).equals(action.item())||!BotItems.ordinary(output)))return fail("Server recipe result differs from the vanilla plan");
        if(action.method()==GatherPlanner.Method.CRAFT&&!output.isEmpty()&&output.getCount()!=action.recipe().count())return fail("Server recipe yield differs from the vanilla plan");
        boolean ready=action.method()==GatherPlanner.Method.CRAFT?!output.isEmpty():!output.isEmpty()&&(menu.slots.get(0).getItem().isEmpty()||BotItems.count(ctx,action.item())+output.getCount()>=batchGoal);
        if(ready){beforeTake=BotItems.count(ctx,action.item());pendingTake=true;click(resultSlot,0,ContainerInput.QUICK_MOVE);delay=5;transition(Stage.VERIFY);return pause();}
        long timeout=action.method()==GatherPlanner.Method.SMELT?90000L:10000L;
        if(System.currentTimeMillis()-lastProgress>timeout)return fail("No workstation progress; check fuel, ingredients, or server recipe changes");
        return pause();
    }
    private void click(int slot,int button,ContainerInput type){ctx.playerController().windowClick(menu.containerId,slot,button,type,ctx.player());delay=2;}
    private void restoreCursor(){
        if(menu==null||menu.getCarried().isEmpty()){ownsCursor=false;sourceSlot=-1;return;}
        if(sourceSlot>=0&&sourceSlot<menu.slots.size()&&(menu.slots.get(sourceSlot).getItem().isEmpty()||ItemStack.isSameItemSameComponents(menu.slots.get(sourceSlot).getItem(),menu.getCarried())))click(sourceSlot,0,ContainerInput.PICKUP);
        ownsCursor=false;sourceSlot=-1;
    }
    public void suspendForConflict(){
        if(!isActive())return;if(ownsCursor)restoreCursor();closeMenu();openRequested=false;fills.clear();transition(Stage.TRAVEL);
    }
    private void closeMenu(){if(menu!=null&&ctx.player()!=null&&ctx.player().containerMenu==menu&&menu.getCarried().isEmpty())ctx.player().closeContainer();menu=null;}
    private PathingCommand finish(){onLostControl();error=null;logDirect("Workstation output confirmed");return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
    private PathingCommand fail(String message){onLostControl();error=message;logDirect("Workstation paused: "+message);return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
    private void transition(Stage next){stage=next;ticks=0;}
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    @Override public void onLostControl(){if(ownsCursor)restoreCursor();closeMenu();stage=Stage.IDLE;action=null;station=null;root=null;fills.clear();openRequested=ownsCursor=touched=pendingTake=false;sourceSlot=-1;}
    @Override public String displayName0(){return "Workstation "+stage+(action==null?"":" "+action.item());}
}

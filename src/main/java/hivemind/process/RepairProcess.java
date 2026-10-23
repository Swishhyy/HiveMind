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
import hivemind.api.pathing.goals.GoalNear;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.CompanionPolicy;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import java.nio.file.Path;
import java.util.Comparator;

/** Hold a damaged Mending pickaxe at an explicitly registered XP point; repairs must appear in server item updates. */
public final class RepairProcess extends HiveMindProcessHelper {
    private boolean active,returning,automatic,atStation;
    private Path root;
    private WorldRoutes.Endpoint station,origin;
    private String item;
    private int slot=-1,previousSlot=-1,delay,lastDurability;
    private long deadline,lastProgress,cooldown,lastActive;
    private String result="No repair requested";
    public RepairProcess(HiveMind bot){super(bot);}
    @Override public boolean isActive(){return active;}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -0.35;}
    public String result(){return result;}
    public void stopWithReason(String reason){if(active)finish(reason,false);}
    private boolean mending(ItemStack stack){return stack.isDamageableItem()&&stack.getDamageValue()>0&&stack.getEnchantments().keySet().stream().anyMatch(holder->holder.is(Enchantments.MENDING));}
    private int candidate(boolean auto){
        int best=-1;double fraction=2;
        for(int i=0;i<36;i++){
            ItemStack stack=ctx.player().getInventory().getItem(i);
            if(!mending(stack)||!BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_pickaxe"))continue;
            int left=stack.getMaxDamage()-stack.getDamageValue();
            if(auto&&CompanionPolicy.repairComplete(left,stack.getMaxDamage(),HiveMind.settings().repairTargetPercent.value))continue;
            if(auto&&left>Math.max(HiveMind.settings().botToolReserve.value+16,HiveMind.settings().repairAtDurability.value))continue;
            double ratio=left/(double)stack.getMaxDamage();if(ratio<fraction){best=i;fraction=ratio;}
        }return best;
    }
    public boolean tryAutomatic(){
        if(active)return true;
        if(!HiveMind.settings().repairToolsBeforeReplace.value||System.currentTimeMillis()<cooldown||ctx.player()==null||MiningRouteAccess.locations(ctx).station("repair")==null)return false;
        int selected=candidate(true);if(selected<0)return false;begin(selected,true);return true;
    }
    public void start(){int selected=candidate(false);if(selected<0)throw new IllegalStateException("Carry a damaged Mending pickaxe to repair");var point=MiningRouteAccess.locations(ctx).station("repair");if(point==null)throw new IllegalStateException("Stand at your XP station and use repair set first");hivemind.getPathingBehavior().cancelEverything();begin(selected,false);}
    private void begin(int selected,boolean auto){
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("Close containers and clear the cursor before repairing");
        station=MiningRouteAccess.locations(ctx).station("repair");root=MiningRouteAccess.root(ctx);origin=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());
        slot=selected;item=BuiltInRegistries.ITEM.getKey(ctx.player().getInventory().getItem(slot).getItem()).toString();previousSlot=ctx.player().getInventory().getSelectedSlot();
        active=true;returning=atStation=false;automatic=auto;delay=0;lastDurability=0;lastProgress=lastActive=System.currentTimeMillis();
        deadline=lastProgress+Math.max(60000L,Math.min(3600000L,HiveMind.settings().companionTaskTimeoutMS.value));result="Traveling to the Mending station";logDirect(result);
    }
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        try{
            if(hivemind.getDepositProcess().isActive())return new PathingCommand(null,PathingCommandType.DEFER);
            long activeNow=System.currentTimeMillis();if(activeNow-lastActive>5000)lastProgress=activeNow;lastActive=activeNow;
            if(ctx.player()==null||ctx.world()==null||!root.equals(MiningRouteAccess.root(ctx)))return finish("Repair stopped: world changed",false);
            if(failed||System.currentTimeMillis()>deadline)return finish("Repair route failed or timed out",false);
            var target=returning?origin:station;
            if(!MiningRouteAccess.dimension(ctx).equals(target.dimension())){hivemind.getPortalTravelProcess().start(target.dimension(),MiningRouteAccess.position(target));return pause();}
            GoalNear goal=new GoalNear(MiningRouteAccess.position(target),2);
            if(!goal.isInGoal(ctx.playerFeet())&&(returning||!atStation||ctx.playerFeet().distSqr(MiningRouteAccess.position(target))>36))return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            if(!safe)return pause();
            hivemind.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
            if(returning)return finish("Pickaxe repaired; returned to work",true);
            if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return finish("Close the container before repair can continue",false);
            ItemStack tool=ctx.player().getInventory().getItem(slot);
            if(!sameTool(tool)){
                slot=-1;for(int i=0;i<36;i++)if(sameTool(ctx.player().getInventory().getItem(i))){slot=i;break;}
                if(slot<0)return finish("The selected Mending pickaxe was removed",false);tool=ctx.player().getInventory().getItem(slot);
            }
            if(slot>=9){if(delay--<=0){ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot,0,ContainerInput.SWAP,ctx.player());slot=0;delay=10;}return pause();}
            if(delay-->0)return pause();
            ctx.player().getInventory().setSelectedSlot(slot);ctx.playerController().syncHeldItem();
            int remaining=tool.getMaxDamage()-tool.getDamageValue();long now=System.currentTimeMillis();
            if(!atStation){atStation=true;lastDurability=remaining;lastProgress=now;}
            if(remaining>lastDurability){lastDurability=remaining;lastProgress=now;}
            if(CompanionPolicy.repairComplete(remaining,tool.getMaxDamage(),HiveMind.settings().repairTargetPercent.value)){returning=true;return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
            if(now-lastProgress>Math.max(10000L,Math.min(600000L,HiveMind.settings().repairNoProgressTimeoutMS.value)))return finish("No repair progress at the XP station; checking replacement supplies",false);
            result="Waiting for XP with Mending pickaxe ("+remaining+"/"+tool.getMaxDamage()+")";
            var orb=ctx.entitiesStream().filter(entity->entity instanceof ExperienceOrb&&entity.isAlive()&&entity.blockPosition().distSqr(MiningRouteAccess.position(station))<=36)
                    .min(Comparator.comparingDouble(entity->entity.distanceToSqr(ctx.player()))).orElse(null);
            if(orb!=null)return new PathingCommand(new GoalNear(orb.blockPosition(),1),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            return pause();
        }catch(RuntimeException e){return finish("Repair stopped: "+e.getMessage(),false);}
    }
    private PathingCommand finish(String message,boolean success){boolean auto=automatic;onLostControl();result=message;if(!success&&auto)cooldown=System.currentTimeMillis()+600000;logDirect(message);return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
    private boolean sameTool(ItemStack stack){return !stack.isEmpty()&&BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)&&stack.getEnchantments().keySet().stream().anyMatch(holder->holder.is(Enchantments.MENDING));}
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    @Override public void onLostControl(){active=returning=atStation=false;if(previousSlot>=0&&ctx.player()!=null){ctx.player().getInventory().setSelectedSlot(previousSlot);if(ctx.minecraft().gameMode!=null)ctx.playerController().syncHeldItem();}previousSlot=-1;root=null;station=origin=null;slot=-1;}
    @Override public String displayName0(){return "Repair: "+result;}
}

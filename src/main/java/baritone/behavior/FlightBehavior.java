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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import baritone.process.ElytraProcess;
import baritone.utils.bot.AutonomyPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/** Optional transport owned by an ordinary roam/search task, never by a second client. */
public final class FlightBehavior extends Behavior {
    private IBaritoneProcess owner;
    private long retryAt,started;
    private String reason="Walking";
    public FlightBehavior(Baritone bot){super(bot);}
    public String status(){return reason;}
    public boolean flying(){return owner!=null&&baritone.getElytraProcess().isActive();}
    public boolean capable(){
        if(ctx.player()==null||ctx.world()==null)return false;var wings=ctx.player().getItemBySlot(EquipmentSlot.CHEST);int rockets=0;
        for(int i=0;i<36;i++)if(ctx.player().getInventory().getItem(i).is(Items.FIREWORK_ROCKET))rockets+=ctx.player().getInventory().getItem(i).getCount();
        return AutonomyPolicy.mayFly(Baritone.settings().botUseElytra.value,baritone.getElytraProcess().isLoaded(),wings.is(Items.ELYTRA),wings.getMaxDamage()-wings.getDamageValue(),rockets,Baritone.settings().botFlightRocketReserve.value,Double.MAX_VALUE,Baritone.settings().botFlightMinimumDistance.value,false,false);
    }
    public boolean travel(IBaritoneProcess task,BlockPos point){
        if(flying())return true;
        if(System.currentTimeMillis()<retryAt||!capable()||ctx.player().isInWater()||ctx.player().isInLava()||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        if(Math.hypot(point.getX()-ctx.player().getX(),point.getZ()-ctx.player().getZ())<Math.max(96,Baritone.settings().botFlightMinimumDistance.value))return false;
        if(!(baritone.getElytraProcess() instanceof ElytraProcess flight)||flight.isActive())return false;
        if(!ctx.player().isFallFlying()&&(!ctx.player().onGround()||!ctx.world().noCollision(ctx.player(),ctx.player().getBoundingBox().expandTowards(0,3,0))))return false;
        try{flight.travelFor(task,new GoalXZ(point.getX(),point.getZ()));owner=task;started=System.currentTimeMillis();retryAt=started+120000;reason="Flying to "+point.getX()+" "+point.getZ();return true;}
        catch(RuntimeException e){reason="Walking: flight unavailable ("+e.getMessage()+")";retryAt=System.currentTimeMillis()+120000;return false;}
    }
    public void cancel(){if(owner!=null&&baritone.getElytraProcess() instanceof ElytraProcess flight&&flight.ownedBy(owner))flight.onLostControl();owner=null;reason="Walking";}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()){cancel();return;}
        if(owner==null)return;
        if(!baritone.getElytraProcess().isActive()){if(owner==baritone.getStalkProcess())baritone.getStalkProcess().transported();owner=null;reason="Walking after flight";return;}
        if(!owner.isActive()){cancel();return;}
        if(baritone.getElytraProcess() instanceof ElytraProcess flight){
            boolean close=baritone.getStalkProcess().isActive()&&ctx.world().players().stream().anyMatch(p->p.getUUID().equals(baritone.getStalkProcess().target())&&p.distanceToSqr(ctx.player())<128*128&&ctx.player().hasLineOfSight(p));
            if(!ctx.player().isFallFlying()&&!Baritone.settings().botUseElytra.value){cancel();return;}
            if(close||System.currentTimeMillis()-started>180000||!Baritone.settings().botUseElytra.value||baritone.getSurvivalProcess().isActive()||baritone.getConflictProcess().isActive())flight.requestLanding();
        }
    }
}

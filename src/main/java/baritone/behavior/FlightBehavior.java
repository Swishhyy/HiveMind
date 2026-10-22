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
import baritone.api.process.IBaritoneProcess;
import baritone.process.ElytraProcess;
import baritone.process.elytra.ElytraBehavior;
import baritone.utils.bot.FlightPolicy;
import baritone.utils.bot.FlightPolicy.Mode;
import baritone.utils.bot.FlightPolicy.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Owned transport, urgent escape and observed-player pursuit using the native flight navigator. */
public final class FlightBehavior extends Behavior {
    private IBaritoneProcess owner;
    private Mode mode=Mode.TRAVEL;
    private long retryAt,escapeRetryAt,started,lastReplan,lastSeen,nextTakeoffSearch;
    private UUID targetId;
    private Point lastTarget,lastDestination;
    private List<BlockPos> threats=List.of();
    private BlockPos takeoff;
    private boolean airborne;
    private Object world;
    private final Map<BlockPos,Long> blockedTakeoffs=new HashMap<>();
    private int armorSlot=-1;
    private ItemStack armor=ItemStack.EMPTY;
    private String reason="Walking";
    public FlightBehavior(Baritone bot){super(bot);}
    public String status(){return reason;}
    public boolean flying(){return owner!=null&&baritone.getElytraProcess().isActive();}
    public boolean escaping(){return flying()&&mode==Mode.ESCAPE;}
    private int rockets(){
        int rockets=0;
        for(int i=0;i<36;i++)if(ElytraBehavior.isFireworks(ctx.player().getInventory().getItem(i)))rockets+=ctx.player().getInventory().getItem(i).getCount();
        return rockets;
    }
    private int wingDurability(ItemStack stack){return stack.is(Items.ELYTRA)?stack.getMaxDamage()-stack.getDamageValue():0;}
    private boolean supplied(Mode requested){
        if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||!Baritone.settings().botUseElytra.value||!baritone.getElytraProcess().isLoaded())return false;
        int durability=wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST));
        if(Baritone.settings().allowInventory.value)for(int i=0;i<36;i++)durability=Math.max(durability,wingDurability(ctx.player().getInventory().getItem(i)));
        return FlightPolicy.supplied(requested,durability,rockets(),Baritone.settings().botFlightRocketReserve.value,Baritone.settings().elytraMinFireworksBeforeLanding.value,Baritone.settings().elytraMinimumDurability.value);
    }
    public boolean capable(){return supplied(Mode.TRAVEL);}
    public boolean travel(IBaritoneProcess task,BlockPos point){
        if(flying())return owner==task;
        if(ctx.player()==null||Math.hypot(point.getX()-ctx.player().getX(),point.getZ()-ctx.player().getZ())<Math.max(96,Baritone.settings().botFlightMinimumDistance.value))return false;
        return start(task,point,Mode.TRAVEL);
    }
    public boolean pursue(IBaritoneProcess task,Player target){
        if(!FlightPolicy.shouldPursue(baritone.getStalkProcess().hunting(),Baritone.settings().botElytraPursuit.value,target.isFallFlying(),
                Math.hypot(target.getX()-ctx.player().getX(),target.getZ()-ctx.player().getZ()),target.getY()-ctx.player().getY())||!ctx.player().hasLineOfSight(target))return false;
        Point next=observe(target);boolean accepted=start(task,position(next),Mode.PURSUIT);
        if(accepted)targetId=target.getUUID();return accepted;
    }
    public boolean escape(IBaritoneProcess task,List<BlockPos> observedThreats){
        if(!Baritone.settings().botElytraCombatEscape.value||observedThreats.isEmpty()||ctx.player()==null)return false;
        threats=List.copyOf(observedThreats);
        if(escaping()&&owner==task)return true;
        return start(task,escapeDestination(),Mode.ESCAPE);
    }
    private BlockPos escapeDestination(){
        Point from=point(ctx.player().blockPosition());
        Point desired=FlightPolicy.escape(from,threats.stream().map(FlightBehavior::point).toList(),Baritone.settings().botFlightEscapeDistance.value,Math.toRadians(ctx.player().getYRot()+90));
        // Shorten a leg near the world border instead of aiming outside it.
        for(double scale=1;scale>=.03125;scale/=2){
            BlockPos dest=BlockPos.containing(from.x()+(desired.x()-from.x())*scale,from.y(),from.z()+(desired.z()-from.z())*scale);
            if(ctx.world().getWorldBorder().isWithinBounds(dest))return dest;
        }
        return ctx.playerFeet();
    }
    private boolean start(IBaritoneProcess task,BlockPos destination,Mode requested){
        long now=System.currentTimeMillis();
        if(ctx.player()==null||ctx.world()==null||baritone.getAutonomy().paused()||now<(requested==Mode.ESCAPE?escapeRetryAt:retryAt)||!(baritone.getElytraProcess() instanceof ElytraProcess flight))return false;
        if(flight.isActive()){
            if(requested!=Mode.ESCAPE&&owner!=task)return false;
            if(owner==task&&mode==requested)return true;
            if(!flight.retargetFor(task,destination,requested==Mode.ESCAPE))return false;
            owner=task;mode=requested;started=now;lastDestination=point(destination);lastReplan=now;reason=label(requested,destination);return true;
        }
        if(!supplied(requested)||ctx.minecraft().gui.screen()!=null||ctx.player().isInWater()||ctx.player().isInLava()
                ||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        if(requested!=Mode.ESCAPE&&(baritone.getConflictProcess().isActive()||baritone.getSurvivalProcess().isActive()||baritone.getSculkProcess().isActive()||ctx.player().isUsingItem()))return false;
        BlockPos launch=ctx.player().isFallFlying()||!ctx.player().onGround()?null:findTakeoff(now);
        if(!ctx.player().isFallFlying()&&ctx.player().onGround()&&launch==null)return false;
        if(!equipWings(requested)){backoff(now,requested);reason="Walking: carried wings could not be equipped";return false;}
        if(ctx.player().isUsingItem()&&ctx.minecraft().gameMode!=null)ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        try{
            if(requested!=Mode.PURSUIT)destination=cruiseDestination(destination,requested);
            flight.travelFor(task,new baritone.api.pathing.goals.GoalBlock(destination),launch,requested==Mode.ESCAPE);
            owner=task;mode=requested;started=lastReplan=now;world=ctx.world();takeoff=launch;airborne=ctx.player().isFallFlying();
            lastDestination=point(destination);reason=label(requested,destination);return true;
        }catch(RuntimeException e){reason="Flight retry: "+e.getMessage();backoff(now,requested);return false;}
    }
    private String label(Mode requested,BlockPos pos){return (requested==Mode.ESCAPE?"Escaping by elytra":requested==Mode.PURSUIT?"Pursuing by elytra":"Flying")+" toward "+pos.getX()+" "+pos.getZ();}
    private BlockPos cruiseDestination(BlockPos destination,Mode requested){
        int y=Math.max(destination.getY()+16,ctx.playerFeet().getY()+(requested==Mode.ESCAPE?32:flying()?0:16));
        if(ctx.world().dimension()==Level.NETHER&&ctx.player().getY()<128)y=Math.min(112,y);
        else if(ctx.world().hasChunkAt(destination))y=Math.max(y,ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING,destination.getX(),destination.getZ())+16);
        int ceiling=ctx.world().dimensionType().minY()+ctx.world().dimensionType().height()-8;
        return new BlockPos(destination.getX(),Math.min(ceiling,y),destination.getZ());
    }
    private void backoff(long now,Mode requested){if(requested==Mode.ESCAPE)escapeRetryAt=now+1000;else retryAt=now+3000;}
    private BlockPos findTakeoff(long now){
        if(now<nextTakeoffSearch)return null;
        nextTakeoffSearch=now+1000;blockedTakeoffs.values().removeIf(until->until<now);
        BlockPos feet=ctx.playerFeet(),best=null;double score=Double.POSITIVE_INFINITY;
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)for(int y=-2;y<=3;y++){
            BlockPos p=feet.offset(x,y,z);if(blockedTakeoffs.containsKey(p)||!ctx.world().hasChunkAt(p)||!ctx.world().getWorldBorder().isWithinBounds(p)
                    ||!ctx.world().getBlockState(p).getFluidState().isEmpty()||!ctx.world().getBlockState(p.above()).getFluidState().isEmpty()
                    ||ctx.world().getBlockState(p.below()).getCollisionShape(ctx.world(),p.below()).isEmpty())continue;
            var box=ctx.player().getBoundingBox().move(p.getX()+.5-ctx.player().getX(),p.getY()-ctx.player().getY(),p.getZ()+.5-ctx.player().getZ()).expandTowards(0,2,0);
            double value=x*x+z*z+Math.abs(y)*4;
            if(value<score&&ctx.world().noCollision(ctx.player(),box)){score=value;best=p.immutable();}
        }
        return best;
    }
    /** Three normal SWAP clicks exchange two inventory slots without leaving an item on the cursor. */
    private void exchangeChest(int source){
        int slot=source<9?source+36:source;
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot,8,ContainerInput.SWAP,ctx.player());
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,6,source<9?source:8,ContainerInput.SWAP,ctx.player());
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot,8,ContainerInput.SWAP,ctx.player());
    }
    private boolean equipWings(Mode requested){
        int minimum=FlightPolicy.durability(requested,Baritone.settings().elytraMinimumDurability.value);
        if(wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST))>=minimum)return true;
        if(!Baritone.settings().allowInventory.value||ctx.minecraft().gameMode==null)return false;
        for(int i=0;i<36;i++)if(wingDurability(ctx.player().getInventory().getItem(i))>=minimum){
            ItemStack previous=ctx.player().getItemBySlot(EquipmentSlot.CHEST).copy();exchangeChest(i);
            if(wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST))<minimum)return false;
            if(!previous.isEmpty()&&!previous.is(Items.ELYTRA)){armor=previous;armorSlot=i;}return true;
        }
        return false;
    }
    private void restoreArmor(){
        if(armorSlot<0||!ctx.player().onGround()||ctx.player().isFallFlying()||ctx.player().containerMenu!=ctx.player().inventoryMenu
                ||!ctx.player().inventoryMenu.getCarried().isEmpty()||ctx.minecraft().gameMode==null)return;
        if(ctx.player().getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)&&ItemStack.isSameItemSameComponents(ctx.player().getInventory().getItem(armorSlot),armor))exchangeChest(armorSlot);
        armorSlot=-1;armor=ItemStack.EMPTY;
    }
    public void cancel(){if(owner!=null&&baritone.getElytraProcess() instanceof ElytraProcess flight&&flight.ownedBy(owner))flight.onLostControl();owner=null;targetId=null;reason="Walking";}
    private Point observe(Player target){
        lastTarget=new Point(target.getX(),target.getY(),target.getZ());lastSeen=System.currentTimeMillis();
        var velocity=target.getDeltaMovement();return FlightPolicy.intercept(lastTarget,new Point(velocity.x,velocity.y,velocity.z));
    }
    private static Point point(BlockPos pos){return new Point(pos.getX(),pos.getY(),pos.getZ());}
    private static BlockPos position(Point point){return BlockPos.containing(point.x(),point.y(),point.z());}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||world!=null&&world!=ctx.world()){
            cancel();world=ctx.world();armorSlot=-1;armor=ItemStack.EMPTY;blockedTakeoffs.clear();return;
        }
        if(owner==null){restoreArmor();return;}
        long now=System.currentTimeMillis();
        if(!baritone.getElytraProcess().isActive()){
            if(baritone.getStalkProcess().isActive())baritone.getStalkProcess().transported();
            if(!airborne){if(takeoff!=null)blockedTakeoffs.put(takeoff,now+30000);backoff(now,mode);reason="Takeoff blocked; walking briefly before retrying";}
            else reason="Landed; ready for the next flight";
            owner=null;targetId=null;restoreArmor();return;
        }
        airborne|=ctx.player().isFallFlying();
        if(baritone.getAutonomy().paused())return;
        if(!owner.isActive()){
            if(ctx.player().isFallFlying()&&baritone.getElytraProcess() instanceof ElytraProcess flight){reason="Landing: parent task is held or finished";flight.requestLanding();}
            else cancel();return;
        }
        if(baritone.getElytraProcess() instanceof ElytraProcess flight){
            if(!ctx.player().isFallFlying()&&!Baritone.settings().botUseElytra.value){cancel();return;}
            if(!Baritone.settings().botUseElytra.value){flight.requestLanding();return;}
            if(mode==Mode.ESCAPE){
                if(!Baritone.settings().botElytraCombatEscape.value){flight.requestLanding();return;}
                boolean pursued=threats.stream().anyMatch(p->p.distSqr(ctx.playerFeet())<512*512);
                if(pursued&&flight.currentDestination()!=null&&flight.currentDestination().distSqr(ctx.playerFeet())<384*384)
                    updateDestination(flight,cruiseDestination(escapeDestination(),Mode.ESCAPE),now);
                return;
            }
            if(baritone.getSurvivalProcess().isActive()||baritone.getConflictProcess().isActive()&&(!ctx.player().isFallFlying()||baritone.getConflictProcess().playerThreat())||now-started>600000){
                reason="Landing: care or conflict needs ground control";flight.requestLanding();return;
            }
            if(owner==baritone.getFollowProcess()){
                var following=baritone.getFollowProcess().following();
                if(following!=null&&!following.isEmpty()){
                    var entity=following.get(0);
                    if(entity.distanceToSqr(ctx.player())<32*32&&!(entity instanceof Player p&&p.isFallFlying())){reason="Landing near the followed entity";flight.requestLanding();}
                    else updateDestination(flight,cruiseDestination(entity.blockPosition(),Mode.TRAVEL),now);
                }
                return;
            }
            UUID watched=owner==baritone.getStalkProcess()?baritone.getStalkProcess().target():targetId;
            Player target=watched==null?null:ctx.world().players().stream().filter(p->p.getUUID().equals(watched)&&p.isAlive()&&!p.isSpectator()&&ctx.player().hasLineOfSight(p)).findFirst().orElse(null);
            if(owner==baritone.getStalkProcess()&&baritone.getStalkProcess().hunting()&&watched!=null&&(baritone.getNetwork().isTeammate(watched)
                    ||!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(watched)||target!=null&&target.isCreative())){
                reason="Landing: target is no longer an eligible opponent";flight.requestLanding();return;
            }
            if(target!=null&&owner==baritone.getStalkProcess()&&baritone.getStalkProcess().hunting()&&target.isFallFlying()&&Baritone.settings().botElytraPursuit.value){mode=Mode.PURSUIT;targetId=watched;}
            if(mode==Mode.PURSUIT){
                if(!Baritone.settings().botElytraPursuit.value){flight.requestLanding();return;}
                Point next=target==null?lastTarget:observe(target);
                if(!FlightPolicy.fresh(now,lastSeen)||next==null){reason="Lost visual pursuit; landing to search the last sighting";flight.requestLanding();return;}
                if(target!=null&&!target.isFallFlying()&&target.distanceToSqr(ctx.player())<48*48){reason="Landing: opponent is back on the ground";flight.requestLanding();return;}
                updateDestination(flight,position(next),now);return;
            }
            if(target!=null){
                int distance=baritone.getStalkProcess().hunting()?24:64;
                if(target.distanceToSqr(ctx.player())<(distance+16)*(distance+16)){reason="Landing near the observed target";flight.requestLanding();}
                else{
                    double dx=ctx.player().getX()-target.getX(),dz=ctx.player().getZ()-target.getZ(),length=Math.max(1,Math.hypot(dx,dz));
                    updateDestination(flight,BlockPos.containing(target.getX()+dx/length*distance,target.getY()+16,target.getZ()+dz/length*distance),now);
                }
            }
        }
    }
    private void updateDestination(ElytraProcess flight,BlockPos destination,long now){
        Point next=point(destination);if(FlightPolicy.replan(now,lastReplan,lastDestination,next)&&flight.retargetFor(owner,destination,mode==Mode.ESCAPE)){
            lastDestination=next;lastReplan=now;reason=label(mode,destination);
        }
    }
}

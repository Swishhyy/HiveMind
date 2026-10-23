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

package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.api.process.IHiveMindProcess;
import hivemind.process.ElytraProcess;
import hivemind.process.elytra.ElytraBehavior;
import hivemind.utils.bot.FlightPolicy;
import hivemind.utils.bot.EquipmentLoadout;
import hivemind.utils.bot.TakeoffState;
import hivemind.utils.bot.AerialSurvey;
import hivemind.utils.bot.FlightPolicy.Mode;
import hivemind.utils.bot.FlightPolicy.Point;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Owned transport, urgent escape and observed-player pursuit using the native flight navigator. */
public final class FlightBehavior extends Behavior {
    private IHiveMindProcess owner;
    private Mode mode=Mode.TRAVEL;
    private long retryAt,escapeRetryAt,started,lastReplan,lastSeen,nextTakeoffSearch;
    private UUID targetId;
    private Point lastTarget,lastDestination;
    private List<BlockPos> threats=List.of();
    private BlockPos takeoff;
    private BlockPos nextSurveyPoint;
    private boolean airborne;
    private Object world;
    private final Map<BlockPos,Long> blockedTakeoffs=new HashMap<>();
    private int armorSlot=-1;
    private ItemStack armor=ItemStack.EMPTY;
    private String reason="Walking";
    public FlightBehavior(HiveMind bot){super(bot);}
    public String status(){return reason;}
    public boolean flying(){return owner!=null&&hivemind.getElytraProcess().isActive();}
    public boolean escaping(){return flying()&&mode==Mode.ESCAPE;}
    private int rockets(){
        int rockets=0;
        for(int i=0;i<36;i++)if(ElytraBehavior.isFireworks(ctx.player().getInventory().getItem(i)))rockets+=ctx.player().getInventory().getItem(i).getCount();
        return rockets;
    }
    private int wingDurability(ItemStack stack){return stack.is(Items.ELYTRA)?stack.getMaxDamage()-stack.getDamageValue():0;}
    private boolean supplied(Mode requested){
        if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||!HiveMind.settings().botUseElytra.value||!hivemind.getElytraProcess().isLoaded())return false;
        int durability=wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST));
        for(int i=0;i<36;i++)durability=Math.max(durability,wingDurability(ctx.player().getInventory().getItem(i)));
        return FlightPolicy.supplied(requested,durability,rockets(),HiveMind.settings().botFlightRocketReserve.value,HiveMind.settings().elytraMinFireworksBeforeLanding.value,HiveMind.settings().elytraMinimumDurability.value);
    }
    public boolean capable(){return !hivemind.getOperations().walking()&&supplied(Mode.TRAVEL);}
    public boolean survey(IHiveMindProcess task,BlockPos point){if(hivemind.getOperations().walking())return false;return start(task,point,Mode.SURVEY);}
    public boolean travel(IHiveMindProcess task,BlockPos point){
        if(hivemind.getOperations().walking())return false;
        if(flying())return owner==task;
        if(ctx.player()==null||Math.hypot(point.getX()-ctx.player().getX(),point.getZ()-ctx.player().getZ())<Math.max(96,HiveMind.settings().botFlightMinimumDistance.value))return false;
        return start(task,point,Mode.TRAVEL);
    }
    public boolean pursue(IHiveMindProcess task,Player target){
        if(!FlightPolicy.shouldPursue(hivemind.getStalkProcess().hunting(),HiveMind.settings().botElytraPursuit.value,target.isFallFlying(),
                Math.hypot(target.getX()-ctx.player().getX(),target.getZ()-ctx.player().getZ()),target.getY()-ctx.player().getY())||hivemind.getAwareness().player(target.getUUID())==null)return false;
        Point next=observe(target);boolean accepted=start(task,position(next),Mode.PURSUIT);
        if(accepted)targetId=target.getUUID();return accepted;
    }
    public boolean escape(IHiveMindProcess task,List<BlockPos> observedThreats){
        if(!HiveMind.settings().botElytraCombatEscape.value||observedThreats.isEmpty()||ctx.player()==null)return false;
        threats=List.copyOf(observedThreats);
        if(escaping()&&owner==task)return true;
        return start(task,escapeDestination(),Mode.ESCAPE);
    }
    private BlockPos escapeDestination(){
        Point from=point(ctx.player().blockPosition());
        Point desired=FlightPolicy.escape(from,threats.stream().map(FlightBehavior::point).toList(),HiveMind.settings().botFlightEscapeDistance.value,Math.toRadians(ctx.player().getYRot()+90));
        // Shorten a leg near the world border instead of aiming outside it.
        for(double scale=1;scale>=.03125;scale/=2){
            BlockPos dest=BlockPos.containing(from.x()+(desired.x()-from.x())*scale,from.y(),from.z()+(desired.z()-from.z())*scale);
            if(ctx.world().getWorldBorder().isWithinBounds(dest))return dest;
        }
        return ctx.playerFeet();
    }
    private boolean start(IHiveMindProcess task,BlockPos destination,Mode requested){
        long now=System.currentTimeMillis();
        if(ctx.player()==null||ctx.world()==null||hivemind.getAutonomy().paused()||now<(requested==Mode.ESCAPE?escapeRetryAt:retryAt)||!(hivemind.getElytraProcess() instanceof ElytraProcess flight))return false;
        if(flight.isActive()){
            if(requested!=Mode.ESCAPE&&owner!=task)return false;
            if(owner==task&&mode==requested)return true;
            if(requested!=Mode.PURSUIT)destination=cruiseDestination(destination,requested);
            if(!flight.retargetFor(task,destination,requested==Mode.ESCAPE))return false;
            owner=task;mode=requested;nextSurveyPoint=null;started=now;lastDestination=point(destination);lastReplan=now;reason=label(requested,destination);return true;
        }
        if(requested!=Mode.ESCAPE&&(hivemind.getShulkerProcess().isActive()||hivemind.getShulkerProcess().hasPendingRecovery()||hivemind.getDepositProcess().isActive()
                ||!hivemind.getOperations().prepareFlight()||!hivemind.getOperations().prepareTrip(destination)))return false;
        if(!supplied(requested)||ctx.minecraft().gui.screen()!=null||ctx.player().isInWater()||ctx.player().isInLava()
                ||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        if(requested!=Mode.ESCAPE&&(hivemind.getConflictProcess().isActive()||hivemind.getSurvivalProcess().isActive()||hivemind.getSculkProcess().isActive()||ctx.player().isUsingItem()))return false;
        if(!ctx.player().isFallFlying()&&!ctx.player().onGround()&&(ctx.player().getDeltaMovement().y>=-.08||!clearTakeoffColumn(ctx.playerFeet())))return false;
        BlockPos launch=ctx.player().isFallFlying()||!ctx.player().onGround()?null:findTakeoff(now);
        if(!ctx.player().isFallFlying()&&ctx.player().onGround()&&launch==null){reason="Walking to clearer ground for takeoff";return false;}
        if(!equipWings(requested)){backoff(now,requested);reason="Walking: carried wings could not be equipped";return false;}
        if(ctx.player().isUsingItem()&&ctx.minecraft().gameMode!=null)ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        try{
            if(requested!=Mode.PURSUIT)destination=cruiseDestination(destination,requested);
            flight.travelFor(task,new hivemind.api.pathing.goals.GoalBlock(destination),launch,requested==Mode.ESCAPE);
            owner=task;mode=requested;started=lastReplan=now;world=ctx.world();takeoff=launch==null?ctx.playerFeet().immutable():launch;airborne=false;
            nextSurveyPoint=null;
            lastDestination=point(destination);reason=label(requested,destination);return true;
        }catch(RuntimeException e){reason="Flight retry: "+e.getMessage();backoff(now,requested);return false;}
    }
    private String label(Mode requested,BlockPos pos){return (requested==Mode.ESCAPE?"Escaping by elytra":requested==Mode.PURSUIT?"Pursuing by elytra":requested==Mode.SURVEY?"Surveying by elytra":"Flying")+" toward "+pos.getX()+" "+pos.getZ();}
    private BlockPos cruiseDestination(BlockPos destination,Mode requested){
        if(requested==Mode.SURVEY){
            BlockPos loaded=ctx.world().hasChunkAt(destination)?destination:ctx.playerFeet();
            int surface=ctx.world().getHeight(Heightmap.Types.MOTION_BLOCKING,loaded.getX(),loaded.getZ());
            int height=Math.max(16,Math.min(128,HiveMind.settings().botFlightSurveyHeight.value));
            int y=surface<=ctx.world().getMinY()+1?Math.max(64,ctx.playerFeet().getY()):surface+height;
            if(ctx.world().dimension()==Level.NETHER&&ctx.player().getY()<128)y=Math.min(112,y);
            return new BlockPos(destination.getX(),Math.min(ctx.world().getMaxY()-8,y),destination.getZ());
        }
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
        for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)for(int y=-2;y<=3;y++){
            double value=x*x+z*z+Math.abs(y)*4;if(value>=score)continue;
            BlockPos p=feet.offset(x,y,z);
            if(blockedTakeoffs.keySet().stream().anyMatch(blocked->TakeoffState.sameFailedArea(point(p),point(blocked)))
                    ||!ctx.world().hasChunkAt(p)||!ctx.world().getWorldBorder().isWithinBounds(p)
                    ||!ctx.world().getBlockState(p).getFluidState().isEmpty()||!ctx.world().getBlockState(p.above()).getFluidState().isEmpty()
                    ||ctx.world().getBlockState(p.below()).getCollisionShape(ctx.world(),p.below()).isEmpty()
                    ||!clearTakeoffColumn(p)||!clearTakeoffExit(p))continue;
            score=value;best=p.immutable();
        }
        return best;
    }
    private boolean clearTakeoffColumn(BlockPos point){
        // Use standing dimensions even if an unsuccessful glide left the player in the short wing pose.
        for(int y=0;y<10;y++)if(!ctx.world().getBlockState(point.above(y)).getFluidState().isEmpty())return false;
        AABB column=new AABB(point.getX()+.1,point.getY(),point.getZ()+.1,point.getX()+.9,point.getY()+9.8,point.getZ()+.9);
        return ctx.world().hasChunkAt(point)&&ctx.world().getWorldBorder().isWithinBounds(point)
                &&(ctx.world().dimension()!=Level.OVERWORLD||ctx.world().canSeeSky(point.above()))&&ctx.world().noCollision(ctx.player(),column);
    }
    private boolean clearTakeoffExit(BlockPos point){
        // A narrow vertical hole through leaves is not an exit for a boosted glider. Require one open lane.
        for(int direction=0;direction<8;direction++){
            double angle=direction*Math.PI/4,dx=Math.cos(angle)*8,dz=Math.sin(angle)*8;
            double cx=point.getX()+.5,cz=point.getZ()+.5;
            AABB lane=new AABB(Math.min(cx,cx+dx)-.5,point.getY()+.8,Math.min(cz,cz+dz)-.5,
                    Math.max(cx,cx+dx)+.5,point.getY()+4.8,Math.max(cz,cz+dz)+.5);
            if(ctx.world().hasChunkAt(BlockPos.containing(lane.minX,lane.minY,lane.minZ))&&ctx.world().hasChunkAt(BlockPos.containing(lane.maxX,lane.minY,lane.maxZ))
                    &&ctx.world().hasChunkAt(BlockPos.containing(lane.minX,lane.minY,lane.maxZ))&&ctx.world().hasChunkAt(BlockPos.containing(lane.maxX,lane.minY,lane.minZ))
                    &&ctx.world().getWorldBorder().isWithinBounds(BlockPos.containing(cx+dx,point.getY(),cz+dz))&&ctx.world().noCollision(ctx.player(),lane))return true;
        }
        return false;
    }
    private void blockTakeoff(BlockPos site,long now){
        hivemind.getOperations().warn(site,"Failed elytra launch",60000);
        blockedTakeoffs.put(site,now+60000);while(blockedTakeoffs.size()>64)blockedTakeoffs.remove(blockedTakeoffs.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey());
    }
    /** Three normal SWAP clicks exchange two inventory slots without leaving an item on the cursor. */
    private void exchangeChest(int source){
        int slot=source<9?source+36:source;
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot,8,ContainerInput.SWAP,ctx.player());
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,6,source<9?source:8,ContainerInput.SWAP,ctx.player());
        if(source>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot,8,ContainerInput.SWAP,ctx.player());
    }
    private boolean equipWings(Mode requested){
        int minimum=FlightPolicy.durability(requested,HiveMind.settings().elytraMinimumDurability.value);
        if(wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST))>=minimum)return true;
        if(ctx.minecraft().gameMode==null||EquipmentLoadout.bound(ctx.player().getItemBySlot(EquipmentSlot.CHEST)))return false;
        for(int i=0;i<36;i++)if(wingDurability(ctx.player().getInventory().getItem(i))>=minimum&&!EquipmentLoadout.bound(ctx.player().getInventory().getItem(i))){
            ItemStack previous=ctx.player().getItemBySlot(EquipmentSlot.CHEST).copy();exchangeChest(i);
            if(wingDurability(ctx.player().getItemBySlot(EquipmentSlot.CHEST))<minimum)return false;
            if(!previous.isEmpty()&&!previous.is(Items.ELYTRA)){armor=previous;armorSlot=i;}return true;
        }
        return false;
    }
    private void restoreArmor(){
        if(HiveMind.settings().botUseElytra.value&&!hivemind.getStalkProcess().committedCombat()&&!hivemind.getConflictProcess().isActive())return;
        if(armorSlot<0||!ctx.player().onGround()||ctx.player().isFallFlying()||ctx.player().containerMenu!=ctx.player().inventoryMenu
                ||!ctx.player().inventoryMenu.getCarried().isEmpty()||ctx.minecraft().gameMode==null)return;
        if(ctx.player().getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)&&ItemStack.isSameItemSameComponents(ctx.player().getInventory().getItem(armorSlot),armor))exchangeChest(armorSlot);
        armorSlot=-1;armor=ItemStack.EMPTY;
    }
    public void cancel(){if(owner!=null&&hivemind.getElytraProcess() instanceof ElytraProcess flight&&flight.ownedBy(owner))flight.onLostControl();owner=null;targetId=null;reason="Walking";}
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
        if(owner==null){restoreArmor();if(hivemind.getOperations().walking())reason="Walking away from a failed launch area before retrying flight";else if(!supplied(Mode.TRAVEL))reason=!HiveMind.settings().botUseElytra.value?"Walking: elytra use is disabled":rockets()<Math.max(HiveMind.settings().botFlightRocketReserve.value,HiveMind.settings().elytraMinFireworksBeforeLanding.value)?"Walking: rockets are below the flight reserve":"Walking: no usable elytra with enough durability";return;}
        long now=System.currentTimeMillis();
        if(hivemind.getElytraProcess() instanceof ElytraProcess flight)airborne|=flight.takeoffCompleted();
        if(!hivemind.getElytraProcess().isActive()){
            if(hivemind.getStalkProcess().isActive())hivemind.getStalkProcess().transported();
            if(!airborne){if(takeoff!=null)blockTakeoff(takeoff,now);blockTakeoff(ctx.playerFeet().immutable(),now);backoff(now,mode);reason="Takeoff obstructed; moving away before trying another launch";}
            else reason="Landed; ready for the next flight";
            owner=null;targetId=null;restoreArmor();return;
        }
        if(hivemind.getAutonomy().paused())return;
        if(!owner.isActive()){
            if(ctx.player().isFallFlying()&&hivemind.getElytraProcess() instanceof ElytraProcess flight){reason="Landing: parent task is held or finished";flight.requestLanding();}
            else cancel();return;
        }
        if(hivemind.getElytraProcess() instanceof ElytraProcess flight){
            if(mode!=Mode.ESCAPE&&airborne&&ctx.player().isFallFlying()&&HiveMind.settings().botAutoResupply.value&&HiveMind.settings().botExpeditionSupplies.value
                &&hivemind.getShulkerProcess().hasPortableItems(hivemind.getOperations().flightRefillTargets(),true)
                &&ctx.world().players().stream().noneMatch(p->p!=ctx.player()&&!p.isSpectator()&&!hivemind.getNetwork().isTeammate(p.getUUID())&&!hivemind.getBotControl().isTrusted(p.getUUID())&&p.distanceToSqr(ctx.player())<96*96)){
                reason="Landing in a safe spot to refill carried shulker reserves";flight.requestLanding();return;
            }
            if(!ctx.player().isFallFlying()&&!HiveMind.settings().botUseElytra.value){cancel();return;}
            if(!HiveMind.settings().botUseElytra.value){flight.requestLanding();return;}
            if(mode==Mode.ESCAPE){
                if(!HiveMind.settings().botElytraCombatEscape.value){flight.requestLanding();return;}
                boolean pursued=threats.stream().anyMatch(p->p.distSqr(ctx.playerFeet())<512*512);
                if(pursued&&flight.currentDestination()!=null&&flight.currentDestination().distSqr(ctx.playerFeet())<384*384)
                    updateDestination(flight,cruiseDestination(escapeDestination(),Mode.ESCAPE),now);
                return;
            }
            if(hivemind.getSurvivalProcess().isActive()||hivemind.getConflictProcess().isActive()&&(!ctx.player().isFallFlying()||hivemind.getConflictProcess().playerThreat())||now-started>600000){
                reason="Landing: care or conflict needs ground control";flight.requestLanding();return;
            }
            if(owner==hivemind.getFollowProcess()){
                var following=hivemind.getFollowProcess().following();
                if(following!=null&&!following.isEmpty()){
                    var entity=following.get(0);
                    if(entity.distanceToSqr(ctx.player())<32*32&&!(entity instanceof Player p&&p.isFallFlying())){reason="Landing near the followed entity";flight.requestLanding();}
                    else updateDestination(flight,cruiseDestination(entity.blockPosition(),Mode.TRAVEL),now);
                }
                return;
            }
            UUID watched=owner==hivemind.getStalkProcess()?hivemind.getStalkProcess().target():targetId;
            if(owner==hivemind.getStalkProcess()&&watched!=null&&ctx.minecraft().getConnection()!=null&&ctx.minecraft().getConnection().getPlayerInfo(watched)==null){
                reason="Target offline; landing to wait near the last known area";flight.requestLanding();return;
            }
            Player target=hivemind.getAwareness().player(watched);
            if(owner==hivemind.getStalkProcess()&&hivemind.getStalkProcess().hunting()&&watched!=null&&(hivemind.getNetwork().isTeammate(watched)
                    ||!HiveMind.settings().killAllowTrustedTargets.value&&hivemind.getBotControl().isTrusted(watched)||target!=null&&target.isCreative())){
                reason="Landing: target is no longer an eligible opponent";flight.requestLanding();return;
            }
            if(target!=null&&owner==hivemind.getStalkProcess())hivemind.getStalkProcess().trackContact(target);
            if(mode==Mode.SURVEY){
                if(target!=null){
                    hivemind.getStalkProcess().surveyContact(target);nextSurveyPoint=null;
                    mode=hivemind.getStalkProcess().hunting()&&target.isFallFlying()&&HiveMind.settings().botElytraPursuit.value?Mode.PURSUIT:Mode.TRAVEL;
                    targetId=target.getUUID();observe(target);
                }else{
                    if(owner==hivemind.getStalkProcess()&&!HiveMind.settings().botAerialSearch.value){flight.requestLanding();return;}
                    if(owner instanceof AerialSurvey survey&&lastDestination!=null&&Math.hypot(lastDestination.x()-ctx.player().getX(),lastDestination.z()-ctx.player().getZ())<80){
                        if(nextSurveyPoint==null)nextSurveyPoint=survey.nextSurveyWaypoint();
                        if(nextSurveyPoint==null){reason="Survey held: landing before continuing on foot";flight.requestLanding();return;}
                        if(updateDestination(flight,cruiseDestination(nextSurveyPoint,Mode.SURVEY),now))nextSurveyPoint=null;
                    }
                    return;
                }
            }
            if(target!=null&&owner==hivemind.getStalkProcess()&&hivemind.getStalkProcess().hunting()&&target.isFallFlying()&&HiveMind.settings().botElytraPursuit.value){mode=Mode.PURSUIT;targetId=watched;}
            if(mode==Mode.PURSUIT){
                if(!HiveMind.settings().botElytraPursuit.value){flight.requestLanding();return;}
                Point next=target==null?lastTarget:observe(target);
                if(!FlightPolicy.fresh(now,lastSeen)||next==null){reason="Lost entity contact; landing to search the last known position";flight.requestLanding();return;}
                if(target!=null&&!target.isFallFlying()&&target.distanceToSqr(ctx.player())<48*48){reason="Landing: opponent is back on the ground";flight.requestLanding();return;}
                updateDestination(flight,position(next),now);return;
            }
            if(target!=null){
                int distance=hivemind.getStalkProcess().hunting()?24:64;
                if(target.distanceToSqr(ctx.player())<(distance+16)*(distance+16)){reason="Landing near the observed target";flight.requestLanding();}
                else{
                    double dx=ctx.player().getX()-target.getX(),dz=ctx.player().getZ()-target.getZ(),length=Math.max(1,Math.hypot(dx,dz));
                    updateDestination(flight,BlockPos.containing(target.getX()+dx/length*distance,target.getY()+16,target.getZ()+dz/length*distance),now);
                }
            }
        }
    }
    private boolean updateDestination(ElytraProcess flight,BlockPos destination,long now){
        Point next=point(destination);if(FlightPolicy.replan(now,lastReplan,lastDestination,next)&&flight.retargetFor(owner,destination,mode==Mode.ESCAPE)){
            lastDestination=next;lastReplan=now;reason=label(mode,destination);return true;
        }
        return false;
    }
}

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
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.TickEvent;
import hivemind.api.pathing.goals.GoalNear;
import hivemind.api.pathing.goals.Goal;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.api.utils.RotationUtils;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.nio.file.Path;
import java.util.*;

/** Command-driven companion tasks use server-observed positions, real inventories, and bounded recovery/handoff state. */
public final class CompanionProcess extends HiveMindProcessHelper implements IBehavior {
    public enum Kind{IDLE,RECOVER,DELIVER,ESCORT,GATHER}
    private record Seen(WorldRoutes.Endpoint point,long time){}
    private Kind kind=Kind.IDLE;
    private Path root;
    private UUID owner,targetId;
    private String targetName,item,status="Idle";
    private int quantity,stageTicks,searchStep,quietTicks,delivered,pendingDrop,pendingEntity=-1;
    private boolean returning,fetching,fetchStarted,handoff,arrivalNotified,mining,finishedMine,working,depositing;
    private WorldRoutes.Endpoint origin,destination;
    private BotLocations.Death death;
    private Map<String,Integer> baseline=Map.of();
    private Set<String> reserved=Set.of();
    private final Set<String> fetched=new HashSet<>();
    private final Map<UUID,Seen> seen=new HashMap<>();
    private Path seenRoot;
    private Map<String,Integer> lastInventory=Map.of(),settlingInventory=Map.of();
    private DeathInventory deathInventory;
    private long respawnAliveAt,inventoryStableAt;
    private Object inventorySyncPlayer;
    private long inventorySyncAt;
    public boolean awaitingDeathInventory(){return deathInventory!=null&&deathInventory.outcome()==DeathInventory.Outcome.PENDING;}
    public String deathInventoryStatus(){if(ctx.player()==null||ctx.world()==null)return "Unknown";return new DeathInventory(MiningRouteAccess.root(ctx)).outcome().name().toLowerCase(Locale.ROOT)+" (mode "+HiveMind.settings().botDeathInventoryMode.value+")";}
    public void onInventoryContents(int id){if(id==0&&ctx.player()!=null){inventorySyncPlayer=ctx.player();inventorySyncAt=System.currentTimeMillis();}}
    private void assessRespawnInventory(long now){
        if(!awaitingDeathInventory())return;
        var saved=MiningRouteAccess.locations(ctx).death();if(saved==null||saved.time()!=deathInventory.time()){deathInventory.abandon();return;}
        if(!ctx.player().isAlive()){respawnAliveAt=inventoryStableAt=0;return;}
        var inventory=captureInventory();if(respawnAliveAt==0){respawnAliveAt=inventoryStableAt=now;settlingInventory=inventory;}
        if(!inventory.equals(settlingInventory)){settlingInventory=inventory;inventoryStableAt=now;}
        String mode=HiveMind.settings().botDeathInventoryMode.value.toLowerCase(Locale.ROOT);
        if(!DeathInventory.ready(mode,inventorySyncPlayer==ctx.player()&&inventorySyncAt>=deathInventory.time(),now-respawnAliveAt,now-inventoryStableAt))return;
        var outcome=mode.equals("keep")?DeathInventory.Outcome.RETAINED:DeathInventory.classify(deathInventory.before(),inventory);
        var missing=mode.equals("keep")?Map.<String,Integer>of():DeathInventory.missing(deathInventory.before(),inventory);
        MiningRouteAccess.locations(ctx).setDeath(missing.isEmpty()?null:new BotLocations.Death(saved.point(),saved.time(),missing,saved.attempts()));
        deathInventory.finish(outcome);
        String detail=missing.isEmpty()?"Inventory retained or empty; no gear retrieval needed":"Missing "+missing.values().stream().mapToInt(Integer::intValue).sum()+" recorded items; use #recover now when safe";
        notifyOwner("Death inventory: "+outcome.name().toLowerCase(Locale.ROOT)+". "+detail);
        hivemind.getNetwork().addMarker("death","Bot death: "+outcome.name().toLowerCase(Locale.ROOT),detail,saved.point(),ctx.player().getUUID(),now);
    }
    private final Set<UUID> failedRecoveryItems=new HashSet<>();
    private final Set<Integer> beforeEntities=new HashSet<>();
    private long deadline,lastSeen,dropTime;
    private GatherPlanner planner;
    public CompanionProcess(HiveMind bot){super(bot);}
    @Override public boolean isActive(){return kind!=Kind.IDLE;}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return -0.6;}
    public Kind kind(){return kind;}
    public boolean isRecovering(){return kind==Kind.RECOVER;}
    public UUID escortedPlayer(){return kind==Kind.ESCORT?targetId:null;}
    public boolean reserves(String id){return isActive()&&reserved.contains(id);}
    public String status(){return status;}
    public void stopWithReason(String reason){if(isActive())fail(reason);}
    private void begin(Kind task){
        if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive())throw new IllegalStateException("Respawn and join a world before starting a task");
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())throw new IllegalStateException("Close containers and clear the cursor before starting a task");
        UUID controller=hivemind.getJobManager().requestOwner();hivemind.getPathingBehavior().cancelEverything();
        root=MiningRouteAccess.root(ctx);owner=controller;kind=task;origin=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());
        deadline=System.currentTimeMillis()+Math.max(60000L,Math.min(3600000L,HiveMind.settings().companionTaskTimeoutMS.value));lastSeen=System.currentTimeMillis();status=task.name().toLowerCase(Locale.ROOT);
    }
    private Player player(UUID id){return ctx.world().players().stream().filter(player->player.getUUID().equals(id)&&player.isAlive()&&!player.isSpectator()).findFirst().orElse(null);}
    private net.minecraft.client.multiplayer.PlayerInfo online(String name){
        if(ctx.minecraft().getConnection()==null)throw new IllegalStateException("No server connection");
        return ctx.minecraft().getConnection().getOnlinePlayers().stream().filter(info->info.getProfile().name().equalsIgnoreCase(name)&&!info.getProfile().id().equals(ctx.player().getUUID())).findFirst().orElseThrow(()->new IllegalArgumentException("That player is not online"));
    }
    public void recover(){
        if(awaitingDeathInventory())throw new IllegalStateException("Waiting for confirmed respawn inventory; use #set botDeathInventoryMode keep/drop only if this server needs an override");
        var saved=MiningRouteAccess.locations(ctx).death();if(saved==null||saved.items().isEmpty())throw new IllegalStateException("No dropped inventory was recorded for this server/save");
        if(saved.attempts()>=Math.max(1,Math.min(10,HiveMind.settings().recoveryMaxAttempts.value)))throw new IllegalStateException("Recovery attempt limit reached; inspect the site before using recover reset");
        begin(Kind.RECOVER);death=new BotLocations.Death(saved.point(),saved.time(),saved.items(),saved.attempts()+1);MiningRouteAccess.locations(ctx).setDeath(death);
        destination=death.point();baseline=captureInventory();reserved=death.items().keySet();notifyOwner("Recovering recorded inventory at "+destination+" (attempt "+death.attempts()+")");
    }
    public void deliver(String name,String requested,int amount,BlockPos meeting){
        var profile=online(name);String id=BotItems.require(requested);CompanionPolicy.quantity(amount);
        Seen previous=seen.get(profile.getProfile().id());Player visible=player(profile.getProfile().id());
        if(visible==null&&meeting==null&&(previous==null||System.currentTimeMillis()-previous.time()>120000))throw new IllegalStateException("Player position is unknown; include meeting coordinates after the quantity");
        WorldRoutes.Endpoint point=meeting!=null?MiningRouteAccess.endpoint(ctx,meeting):visible!=null?MiningRouteAccess.endpoint(ctx,visible.blockPosition()):previous.point();
        begin(Kind.DELIVER);targetId=profile.getProfile().id();targetName=profile.getProfile().name();item=id;quantity=amount;destination=point;reserved=Set.of(item);
        status="Fetching "+amount+" "+item+" for "+targetName;notifyOwner(status);
    }
    public void escortAt(String name,WorldRoutes.Endpoint point){var profile=online(name);begin(Kind.ESCORT);targetId=profile.getProfile().id();targetName=profile.getProfile().name();destination=point;notifyOwner("Meeting and escorting "+targetName);}
    public void escort(String name){var profile=online(name);Player visible=player(profile.getProfile().id());if(visible==null)throw new IllegalStateException("Escort requires a player currently visible to this client");begin(Kind.ESCORT);targetId=profile.getProfile().id();targetName=profile.getProfile().name();destination=MiningRouteAccess.endpoint(ctx,visible.blockPosition());notifyOwner("Escorting "+targetName);}
    public GatherPlanner.Plan plan(String requested,int amount){var recipes=new VanillaRecipes();return new GatherPlanner(recipes::recipes,HiveMind.settings().gatherMaxActions.value).plan(BotItems.require(requested),amount,BotItems.inventory(ctx));}
    public void gather(String requested,int amount){String id=BotItems.require(requested);CompanionPolicy.quantity(amount);var recipes=new VanillaRecipes();var next=new GatherPlanner(recipes::recipes,HiveMind.settings().gatherMaxActions.value);var planned=next.plan(id,amount,BotItems.inventory(ctx));begin(Kind.GATHER);item=id;quantity=amount;planner=next;reserved=planned.required().keySet();status="Gathering "+amount+" "+id;notifyOwner(status);}
    public void confirmHandoff(){
        if(kind!=Kind.DELIVER)throw new IllegalStateException("No delivery is running");UUID caller=hivemind.getJobManager().requestOwner();
        if(caller!=null&&!caller.equals(targetId)&&!caller.equals(owner))throw new IllegalStateException("Only the recipient or delivery controller can confirm handoff");
        handoff=true;notifyOwner("Handoff confirmed; waiting for a clear, close recipient");
    }
    public void mined(){if(kind==Kind.GATHER&&mining)finishedMine=true;}
    public void recordDeath(){
        if(ctx.player()==null||ctx.world()==null||ctx.worldData()==null)return;
        try{
            int attempts=kind==Kind.RECOVER&&death!=null?death.attempts():0;
            var current=captureInventory();
            var record=new BotLocations.Death(MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),System.currentTimeMillis(),current.isEmpty()?lastInventory:current,attempts);
            deathInventory=new DeathInventory(MiningRouteAccess.root(ctx));deathInventory.record(record.time(),record.items());
            respawnAliveAt=inventoryStableAt=0;inventorySyncPlayer=null;inventorySyncAt=0;
            MiningRouteAccess.locations(ctx).setDeath(record);notifyOwner("Death recorded at "+record.point()+"; checking retained items after respawn before offering recovery");
        }catch(RuntimeException e){logDirect("Could not save recovery point: "+e.getMessage());}
    }
    private Map<String,Integer> captureInventory(){
        Map<String,Integer> inventory=new HashMap<>();for(int i=0;i<36;i++){ItemStack stack=ctx.player().getInventory().getItem(i);if(!stack.isEmpty())inventory.merge(BotItems.id(stack),stack.getCount(),Integer::sum);}
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET,EquipmentSlot.OFFHAND)){var stack=ctx.player().getItemBySlot(slot);if(!stack.isEmpty())inventory.merge(BotItems.id(stack),stack.getCount(),Integer::sum);}return inventory;
    }
    private int recovered(){var inventory=captureInventory();return death.items().entrySet().stream().mapToInt(entry->Math.min(entry.getValue(),Math.max(0,inventory.getOrDefault(entry.getKey(),0)-baseline.getOrDefault(entry.getKey(),0)))).sum();}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        try{
            if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||!root.equals(MiningRouteAccess.root(ctx)))return fail("Task stopped: player or world changed");
            if(System.currentTimeMillis()>deadline)return fail("Task timed out; carried items remain in the inventory");
            if(hivemind.getDepositProcess().isActive()||hivemind.getWorkstationProcess().isActive()||hivemind.getRepairProcess().isActive())return defer();
            if(returning)return travel(origin,failed,safe,true);
            return switch(kind){case RECOVER->recoverTick(failed,safe);case DELIVER->deliverTick(failed,safe);case ESCORT->escortTick(failed,safe);case GATHER->gatherTick(failed,safe);default->null;};
        }catch(RuntimeException e){return fail(e.getMessage());}
    }
    private PathingCommand travel(WorldRoutes.Endpoint point,boolean failed,boolean safe,boolean finish){
        if(!MiningRouteAccess.dimension(ctx).equals(point.dimension())){if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(point.dimension());return defer();}
        if(failed)return fail("No usable route to "+point);
        GoalNear goal=new GoalNear(MiningRouteAccess.position(point),2);
        if(goal.isInGoal(ctx.playerFeet())&&safe){if(finish){notifyOwner(status+"; returned to the starting point");onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}return null;}
        return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand recoverTick(boolean failed,boolean safe){
        int found=recovered(),expected=death.items().values().stream().mapToInt(Integer::intValue).sum();status="Recovered "+found+"/"+expected+" recorded items";
        if(found>=expected){MiningRouteAccess.locations(ctx).setDeath(null);returning=true;notifyOwner(status);return pause();}
        PathingCommand route=travel(destination,false,safe,false);if(route!=null&&stageTicks==0)return failed?fail("No safe route to the death point"):route;
        stageTicks++;
        if(ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).noneMatch(ItemStack::isEmpty))return fail("Recovery inventory is full; free space before retrying");
        int radius=Math.max(4,Math.min(32,HiveMind.settings().recoverySearchRadius.value));
        List<ItemEntity> drops=ctx.entitiesStream().filter(entity->entity instanceof ItemEntity&&entity.isAlive()).map(entity->(ItemEntity)entity)
                .filter(entity->death.items().containsKey(BotItems.id(entity.getItem()))&&entity.blockPosition().distSqr(MiningRouteAccess.position(destination))<=radius*radius&&!failedRecoveryItems.contains(entity.getUUID()))
                .sorted(Comparator.comparingDouble(entity->entity.distanceToSqr(ctx.player()))).toList();
        if(!drops.isEmpty()){
            if(failed){failedRecoveryItems.add(drops.get(0).getUUID());return pause();}
            quietTicks=0;return new PathingCommand(new GoalNear(drops.get(0).blockPosition(),0),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if(++quietTicks<40)return pause();
        if(searchStep>=8){returning=true;notifyOwner(status+"; no more matching drops were found in the bounded search");return pause();}
        double angle=searchStep*Math.PI/4;BlockPos point=MiningRouteAccess.position(destination).offset((int)Math.round(Math.cos(angle)*radius),0,(int)Math.round(Math.sin(angle)*radius));var goal=new GoalNear(point,2);
        if(goal.isInGoal(ctx.playerFeet())||failed){searchStep++;quietTicks=0;return pause();}
        return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand deliverTick(boolean failed,boolean safe){
        if(fetching){fetching=false;if(BotItems.count(ctx,item)<quantity)return fail("Storage did not provide enough "+item+"; carrying "+BotItems.count(ctx,item)+"/"+quantity);}
        if(!fetchStarted){fetchStarted=true;if(BotItems.count(ctx,item)<quantity){fetching=true;hivemind.getDepositProcess().startFetch(Map.of(item,quantity));return defer();}}
        var target=player(targetId);
        if(target!=null){destination=MiningRouteAccess.endpoint(ctx,target.blockPosition());lastSeen=System.currentTimeMillis();}
        if(target==null){PathingCommand route=travel(destination,failed,safe,false);if(route!=null)return route;return pause();}
        if(failed)return fail("No safe route to the delivery recipient");
        int distance=Math.max(2,Math.min(6,HiveMind.settings().deliveryApproachDistance.value));
        if(target.distanceToSqr(ctx.player())>distance*distance)return new PathingCommand(new GoalNear(target.blockPosition(),distance),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        if(!safe)return pause();hivemind.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
        status="Delivery to "+targetName+": "+delivered+"/"+quantity+" received";
        if(delivered>=quantity){notifyOwner(status);onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
        if(!arrivalNotified){arrivalNotified=true;notifyOwner("Delivery arrived for "+targetName+"; use deliver handoff to release the requested items");}
        if(pendingDrop>0){
            discoverDrop();if(System.currentTimeMillis()-dropTime>60000)return fail("Handoff pickup was not confirmed; "+status);return pause();
        }
        if(!handoff&&HiveMind.settings().deliveryRequireHandoff.value)return pause();
        boolean alone=ctx.world().players().stream().noneMatch(other->other!=ctx.player()&&!other.getUUID().equals(targetId)&&other.distanceToSqr(ctx.player())<64);
        if(!CompanionPolicy.mayHandoff(target.getUUID().equals(targetId),target.distanceToSqr(ctx.player())<=16,ctx.player().hasLineOfSight(target),alone,ctx.player().getDeltaMovement().multiply(1,0,1).lengthSqr()<0.0001))return pause();
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return fail("Clear inventory menus and the cursor before handoff");
        var rotation=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),target.position().add(0,0.4,0),ctx.playerRotations());hivemind.getLookBehavior().updateTarget(rotation,true);
        if(!ctx.playerRotations().isReallyCloseTo(rotation))return pause();
        int slot=BotItems.slot(ctx,item);if(slot<0)return fail("Delivery payload is missing; "+status);
        ItemStack stack=ctx.player().getInventory().getItem(slot);int button=CompanionPolicy.dropButton(stack.getCount(),quantity-delivered);
        pendingDrop=button==1?stack.getCount():1;pendingEntity=-1;beforeEntities.clear();ctx.entitiesStream().forEach(entity->beforeEntities.add(entity.getId()));dropTime=System.currentTimeMillis();
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot<9?slot+36:slot,button,ContainerInput.THROW,ctx.player());return pause();
    }
    private void discoverDrop(){
        if(pendingEntity>=0)return;
        var drops=ctx.entitiesStream().filter(entity->entity instanceof ItemEntity&&entity.isAlive()&&!beforeEntities.contains(entity.getId())&&entity.distanceToSqr(ctx.player())<25)
                .map(entity->(ItemEntity)entity).filter(entity->BotItems.id(entity.getItem()).equals(item)&&entity.getItem().getCount()<=pendingDrop).toList();
        if(drops.size()==1)pendingEntity=drops.get(0).getId();
    }
    public void onPickup(int entityId,int collectorId,int amount){
        if(kind!=Kind.DELIVER||pendingDrop<1)return;discoverDrop();if(pendingEntity!=entityId)return;
        Player target=player(targetId);
        if(target!=null&&collectorId==target.getId()){
            int accepted=Math.min(Math.max(0,amount),pendingDrop);delivered+=accepted;pendingDrop-=accepted;if(pendingDrop==0){pendingEntity=-1;handoff=handoff||!HiveMind.settings().deliveryRequireHandoff.value;}
        }else if(collectorId==ctx.player().getId()){pendingDrop=0;pendingEntity=-1;handoff=false;notifyOwner("Bot picked the handoff back up; move closer and confirm handoff again");}
        else fail("Another entity collected the handoff; "+delivered+"/"+quantity+" were confirmed for the recipient");
    }
    private PathingCommand escortTick(boolean failed,boolean safe){
        Player target=player(targetId);
        if(target==null){if(System.currentTimeMillis()-lastSeen>Math.max(10000L,Math.min(600000L,HiveMind.settings().stalkSearchTimeoutMS.value)))return fail("Escort target left client view");PathingCommand route=travel(destination,failed,safe,false);return route==null?pause():route;}
        deadline=System.currentTimeMillis()+Math.max(60000L,Math.min(3600000L,HiveMind.settings().companionTaskTimeoutMS.value));lastSeen=System.currentTimeMillis();destination=MiningRouteAccess.endpoint(ctx,target.blockPosition());
        if(failed)return fail("No safe route to the escort target");
        int distance=Math.max(3,Math.min(16,HiveMind.settings().escortDistance.value));BlockPos center=target.blockPosition();
        Goal ring=new Goal(){@Override public boolean isInGoal(int x,int y,int z){double d=Math.hypot((double)x-center.getX(),(double)z-center.getZ());return d>=Math.max(1,distance-2)&&d<=distance+1&&Math.abs(y-center.getY())<=4;}@Override public double heuristic(int x,int y,int z){double d=Math.hypot((double)x-center.getX(),(double)z-center.getZ());return Math.max(0,Math.max(distance-2-d,d-distance-1))*HiveMind.settings().costHeuristic.value;}};
        status="Escorting "+targetName+" ("+distance+" blocks)";
        if(ring.isInGoal(ctx.playerFeet())&&safe){hivemind.getInputOverrideHandler().clearAllKeys();return pause();}
        return new PathingCommand(ring,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand gatherTick(boolean failed,boolean safe){
        if(fetching){fetching=false;if(hivemind.getDepositProcess().lastFetchError()!=null)throw new IllegalStateException(hivemind.getDepositProcess().lastFetchError());}
        if(working){working=false;if(hivemind.getWorkstationProcess().error()!=null)throw new IllegalStateException(hivemind.getWorkstationProcess().error());}
        if(depositing){depositing=false;if(hivemind.getDepositProcess().lastDepositError()!=null)throw new IllegalStateException(hivemind.getDepositProcess().lastDepositError());notifyOwner("Gathered and deposited "+quantity+" "+item);onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
        if(mining){if(hivemind.getMineProcess().isActive())return defer();if(!finishedMine)return fail("Resource mining stopped before its target was reached");mining=finishedMine=false;}
        if(BotItems.count(ctx,item)>=quantity){
            if(MiningRouteAccess.deposits(ctx).isEmpty()){notifyOwner("Gathered "+quantity+" "+item+"; carrying it because no storage is registered");onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);}
            hivemind.getDepositProcess().startProductDeposit(item);depositing=true;return defer();
        }
        var plan=planner.plan(item,quantity,BotItems.inventory(ctx));reserved=plan.required().keySet();
        for(var entry:plan.required().entrySet())if(!fetched.contains(entry.getKey())&&BotItems.count(ctx,entry.getKey())<entry.getValue()){
            fetched.add(entry.getKey());
            if(!MiningRouteAccess.deposits(ctx).isEmpty()){hivemind.getDepositProcess().startFetch(Map.of(entry.getKey(),entry.getValue()));fetching=true;return defer();}
        }
        if(plan.actions().isEmpty())return fail("No gathering action was produced for the remaining items");
        var action=plan.actions().get(0);status="Gathering "+quantity+" "+item+": "+action.method()+" "+action.item();
        if(action.method()==GatherPlanner.Method.ACQUIRE){
            var blocks=GatherSources.blocks(action.item());if(blocks.isEmpty())return fail("Supply "+action.amount()+" "+action.item()+" in registered storage; no safe mining source or supported recipe is available");
            hivemind.getMineProcess().mineForCompanion(action.item(),BotItems.count(ctx,action.item())+action.amount(),GatherSources.dimension(action.item(),MiningRouteAccess.dimension(ctx)),blocks);mining=true;finishedMine=false;return defer();
        }
        hivemind.getWorkstationProcess().start(action);working=true;return defer();
    }
    private void notifyOwner(String message){logDirect(message);if(owner!=null)hivemind.getBotControl().reply(owner,message);}
    private PathingCommand fail(String message){
        notifyOwner("Companion task stopped: "+message);hivemind.getMineProcess().cancel();
        hivemind.getDepositProcess().onLostControl();hivemind.getWorkstationProcess().onLostControl();hivemind.getRepairProcess().onLostControl();
        hivemind.getPortalTravelProcess().onLostControl();hivemind.getSurvivalProcess().onLostControl();onLostControl();
        return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    private PathingCommand pause(){return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
    private PathingCommand defer(){return new PathingCommand(null,PathingCommandType.DEFER);}
    @Override public void onTick(TickEvent event){
        if(event.getType()==TickEvent.Type.OUT||ctx.world()==null||ctx.player()==null){
            if(!hivemind.getPortalTravelProcess().canPreserveTransition(null)){seen.clear();seenRoot=null;lastInventory=Map.of();deathInventory=null;respawnAliveAt=inventoryStableAt=0;inventorySyncPlayer=null;inventorySyncAt=0;}
            return;
        }
        if(ctx.worldData()==null)return;
        Path currentRoot=MiningRouteAccess.root(ctx);if(!currentRoot.equals(seenRoot)){seen.clear();lastInventory=Map.of();seenRoot=currentRoot;deathInventory=new DeathInventory(currentRoot);respawnAliveAt=inventoryStableAt=0;}
        assessRespawnInventory(System.currentTimeMillis());
        if(ctx.player().isAlive())lastInventory=captureInventory();
        if(isActive()&&nowExpired()&&hivemind.getPathingControlManager().mostRecentInControl().map(process->process.priority()<0).orElse(true)){
            fail("Task timed out; inspect supplies and issue a fresh command");hivemind.getPathingBehavior().softCancelIfSafe();
        }
        long now=System.currentTimeMillis();seen.entrySet().removeIf(entry->now-entry.getValue().time()>120000||!entry.getValue().point().dimension().equals(MiningRouteAccess.dimension(ctx)));
        for(Player player:ctx.world().players())if(player!=ctx.player()&&player.isAlive())seen.put(player.getUUID(),new Seen(MiningRouteAccess.endpoint(ctx,player.blockPosition()),now));
    }
    private boolean nowExpired(){return System.currentTimeMillis()>deadline;}
    @Override public void onLostControl(){kind=Kind.IDLE;root=null;owner=targetId=null;targetName=item=null;quantity=stageTicks=searchStep=quietTicks=delivered=pendingDrop=0;pendingEntity=-1;returning=fetching=fetchStarted=handoff=arrivalNotified=mining=finishedMine=working=depositing=false;origin=destination=null;death=null;baseline=Map.of();reserved=Set.of();fetched.clear();failedRecoveryItems.clear();beforeEntities.clear();planner=null;status="Idle";}
    @Override public String displayName0(){return status;}
}

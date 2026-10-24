/* HiveMind, LGPL-3.0. */
package hivemind.process;
import hivemind.HiveMind;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.api.process.*;
import hivemind.api.pathing.goals.GoalNear;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.network.*;
import hivemind.utils.social.BlastPolicy;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Temporary recovery and expedition preparation. Parent tasks remain owned and resume afterward. */
public final class OperationsProcess extends HiveMindProcessHelper implements IBehavior {
    private final RecoveryPolicy progress=new RecoveryPolicy();
    private final ResupplyPolicy.Retry stockRetry=new ResupplyPolicy.Retry();
    private final Map<String,OperationsPackets.Obstacle> obstacles=new ConcurrentHashMap<>();
    private BlockPos sidestep;
    private long recoveryUntil,walkUntil,nextSupply,supplyDeadline;
    private long groundRecoveryStarted;
    private BlockPos groundRecoveryOrigin;
    private boolean supplying,baseTrip,returningFromBase,supplyPortableOnly;
    private hivemind.utils.mining.WorldRoutes.Endpoint baseDestination,supplyOrigin;
    private long blockerUntil;
    private Map<String,Integer> supplyTargets=Map.of();
    private Map<String,Integer> supplyBefore=Map.of();
    private String blocker="",next="Continue current task";
    private int recoveries;
    private UUID waitingDelivery;
    private String deliveryItem;
    private int deliveryExpected;
    private long deliveryDeadline,nextDelivery;
    private boolean outageReturn;
    private hivemind.utils.mining.WorldRoutes.Endpoint outageBase;
    private boolean recalling;
    private hivemind.utils.mining.WorldRoutes.Endpoint recallBase;
    public boolean recalling(){return recalling;}
    public void cancelRecall(){recalling=false;recallBase=null;}
    public boolean requestRecall(){
        if(recalling)return true;if(ctx.player()==null||ctx.world()==null||hivemind.getShulkerProcess().isActive()||hivemind.getShulkerProcess().blocksTravel()||hivemind.getDepositProcess().isActive()||hivemind.getWorkstationProcess().isActive())return false;
        var base=bestBase();if(base==null){blocker="Recall waiting for a registered usable base";next="Register a base, or resume the retained task";blockerUntil=System.currentTimeMillis()+10000;return false;}recallBase=base.point();recalling=true;blocker="Returning to a known base on recall";next="Hold the original task at the base until resume";return true;
    }
    public boolean returnForOutage(){
        if(outageReturn)return true;if(supplying||waitingDelivery!=null||busy()||hivemind.getShulkerProcess().blocksTravel())return false;
        var base=bestBase();if(base==null)return false;
        if(hivemind.getFlight().flying()){if(hivemind.getElytraProcess() instanceof ElytraProcess flight)flight.requestLanding();return false;}
        if(!ctx.player().onGround())return false;outageBase=base.point();outageReturn=true;blocker="Controller disconnected; returning to a known base";next="Hold at the base until the controller reconnects";return true;
    }
    private java.nio.file.Path serverRoot;
    private java.util.UUID loadoutAccount;
    private TaskLoadoutStore kits;
    private ActivityJournal journal;
    public OperationsProcess(HiveMind bot){super(bot);}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return .3;}
    @Override public boolean isActive(){return !hivemind.isPlayerClient()&&(recalling||outageReturn||waitingDelivery!=null||supplying||System.currentTimeMillis()<recoveryUntil);}
    public boolean walking(){long now=System.currentTimeMillis();if(walkUntil==0)return false;double distance=groundRecoveryOrigin==null||ctx.player()==null?0:Math.hypot((double)ctx.playerFeet().getX()-groundRecoveryOrigin.getX(),(double)ctx.playerFeet().getZ()-groundRecoveryOrigin.getZ());if(!RecoveryPolicy.groundOnly(now,groundRecoveryStarted,walkUntil,distance)){walkUntil=0;groundRecoveryOrigin=null;return false;}return true;}
    public boolean transporting(){return recalling||baseTrip||outageReturn;}
    public String blocker(){return recalling||outageReturn||waitingDelivery!=null||supplying||System.currentTimeMillis()<blockerUntil?blocker:"";}public String nextAction(){return recalling||outageReturn||waitingDelivery!=null||supplying||System.currentTimeMillis()<blockerUntil?next:"Continue current task";}public int recoveries(){return recoveries;}
    private String key(OperationsPackets.Obstacle p){return p.point().dimension()+":"+p.point().x()+":"+p.point().y()+":"+p.point().z();}
    public void accept(OperationsPackets.Obstacle p,long now){if(!p.valid(now)||p.until()<now)return;obstacles.values().removeIf(o->o.until()<now);if(obstacles.size()<128||obstacles.containsKey(key(p)))obstacles.put(key(p),p);}
    public Map<Long,Double> penalties(String dimension){Map<Long,Double> costs=new HashMap<>();long now=System.currentTimeMillis();for(var o:obstacles.values())if(o.until()>now&&o.point().dimension().equals(dimension))for(int x=-2;x<=2;x++)for(int y=-3;y<=3;y++)for(int z=-2;z<=2;z++)costs.put(BlockPos.asLong(o.point().x()+x,o.point().y()+y,o.point().z()+z),120D);return Map.copyOf(costs);}
    public void warn(BlockPos point,String reason,long ttl){if(hivemind.isPlayerClient()||ctx.world()==null||ctx.player()==null)return;long now=System.currentTimeMillis();var who=hivemind.getNetwork().localIdentity();var warning=new OperationsPackets.Obstacle(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,point),reason,now+Math.max(1000,Math.min(600000,ttl)),now);String key=key(warning);var previous=obstacles.get(key);if(previous!=null&&now-previous.observedAt()<5000)return;accept(warning,now);hivemind.getNetwork().publishOperation(warning);}
    public boolean placementBlocked(BlockPos point){String dimension=MiningRouteAccess.dimension(ctx);long now=System.currentTimeMillis();return obstacles.values().stream().anyMatch(o->o.until()>now&&o.reason().startsWith("Placement")&&o.point().dimension().equals(dimension)&&o.point().x()==point.getX()&&o.point().y()==point.getY()&&o.point().z()==point.getZ());}
    public double penalty(String dimension,BlockPos point){long now=System.currentTimeMillis();return obstacles.values().stream().anyMatch(o->o.until()>now&&o.point().dimension().equals(dimension)&&Math.abs(point.getX()-o.point().x())<=2&&Math.abs(point.getY()-o.point().y())<=3&&Math.abs(point.getZ()-o.point().z())<=2)?120:0;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        long now=System.currentTimeMillis();
        if(hivemind.getConflictProcess().isActive()||hivemind.getClutch().active()||!recalling&&hivemind.getStalkProcess().committedCombat())return new PathingCommand(null,PathingCommandType.DEFER);
        if(recalling){
            if(hivemind.getFlight().flying()&&hivemind.getElytraProcess() instanceof ElytraProcess flight&&!flight.ownedBy(this)){flight.requestLanding();return new PathingCommand(null,PathingCommandType.DEFER);}
            if(!recallBase.dimension().equals(MiningRouteAccess.dimension(ctx))){try{if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(recallBase.dimension(),MiningRouteAccess.position(recallBase));return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException error){blocker="Recall waiting for portal: "+error.getMessage();return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}}
            GoalNear goal=new GoalNear(MiningRouteAccess.position(recallBase),3);if(goal.isInGoal(ctx.playerFeet())&&ctx.player().onGround()){recalling=false;hivemind.getReliability().checkpoint().hold(true);hivemind.getAutonomy().pause(true);journal().note("Recall","Reached base; original task held until resume");return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(hivemind.getFlight().travel(this,MiningRouteAccess.position(recallBase)))return new PathingCommand(null,PathingCommandType.DEFER);return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if(outageReturn){
            if(hivemind.getNetwork().controllerConnected()){outageReturn=false;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(!outageBase.dimension().equals(MiningRouteAccess.dimension(ctx))){try{if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(outageBase.dimension(),MiningRouteAccess.position(outageBase));return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException unavailable){hivemind.getReliability().outageAtBase();outageReturn=false;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}}
            GoalNear goal=new GoalNear(MiningRouteAccess.position(outageBase),3);if(goal.isInGoal(ctx.playerFeet())){outageReturn=false;hivemind.getReliability().outageAtBase();return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(hivemind.getFlight().travel(this,MiningRouteAccess.position(outageBase)))return new PathingCommand(null,PathingCommandType.DEFER);
            return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if(waitingDelivery!=null){
            var receipt=hivemind.getNetwork().controlResult(waitingDelivery);
            if(BotItems.supplyCount(ctx,deliveryItem)>=deliveryExpected){hivemind.getReliability().success("supply-delivery","Requested supplies arrived in the recipient inventory");waitingDelivery=null;blocker="";nextSupply=now+60000;next="Resume original task";}
            else if(now>=deliveryDeadline||receipt!=null&&!receipt.success()){waitingDelivery=null;nextSupply=0;blocker="Supply delivery unavailable; using carried or base stock";blockerUntil=now+30000;next="Reassess another supply source";}
            return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        }
        if(supplying&&baseTrip){
            if(now>=supplyDeadline){abortBase("Bot base trip timed out");return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            var destination=returningFromBase?supplyOrigin:baseDestination;
            if(!destination.dimension().equals(MiningRouteAccess.dimension(ctx))){try{if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(destination.dimension(),MiningRouteAccess.position(destination));return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException error){abortBase("Base route unavailable: "+error.getMessage());return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}}
            GoalNear goal=new GoalNear(MiningRouteAccess.position(destination),3);if(!goal.isInGoal(ctx.playerFeet())){if(hivemind.getFlight().travel(this,MiningRouteAccess.position(destination)))return new PathingCommand(null,PathingCommandType.DEFER);return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);}
            if(returningFromBase){baseTrip=returningFromBase=supplying=false;nextSupply=now+60000;next="Resume original task";return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(!safeRestock())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
            BotBaseAccess.scan(ctx);baseTrip=false;
            try{hivemind.getDepositProcess().startExpeditionFetch(supplyTargets);blocker="Restocking from bot base containers";return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException error){blocker="Base stock unavailable: "+error.getMessage();baseTrip=returningFromBase=true;blockerUntil=now+60000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        }
        if(supplying)return finishSupply(now);
        if(sidestep!=null&&now<recoveryUntil&&!failed)return new PathingCommand(new GoalNear(sidestep,1),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
    }
    private PathingCommand finishSupply(long now){
        boolean timedOut=hivemind.getDepositProcess().isActive()&&now>=supplyDeadline;
        if(timedOut)hivemind.getDepositProcess().onLostControl();
        if(hivemind.getDepositProcess().isActive())return new PathingCommand(null,PathingCommandType.DEFER);
        supplying=false;
        String error=timedOut?"Supply trip timed out":hivemind.getDepositProcess().lastFetchError();
        var after=ExpeditionLoadout.counts(ctx,supplyTargets.keySet());
        boolean progressed=after.entrySet().stream().anyMatch(e->e.getValue()>supplyBefore.getOrDefault(e.getKey(),0));
        if(error==null&&(!supplyPortableOnly||!progressed)&&supplyTargets.entrySet().stream().anyMatch(e->!SupplyReserves.refilled(e.getKey(),e.getValue(),after.getOrDefault(e.getKey(),0))))
            error="accessible stock did not fill the requested supplies";
        if(!supplyPortableOnly||!progressed||error!=null)stockRetry.finished(supplyTargets,after,now);
        blocker=error==null?"":"Supplies unavailable: "+error;
        next=error==null?"Resume original task":supplyPortableOnly&&progressed?
                "Continue with refilled carried stock; reassess remaining goals later":
                "Continue with carried supplies; defer unchanged shortages for 10 minutes";
        nextSupply=now+60000;blockerUntil=nextSupply;
        if(supplyOrigin!=null&&!MiningRouteAccess.endpoint(ctx,ctx.playerFeet()).equals(supplyOrigin))baseTrip=returningFromBase=supplying=true;
        return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
    }
    private TaskLoadoutStore.Mode activeKit(){
        if(hivemind.getStalkProcess().hunting())return TaskLoadoutStore.Mode.HUNT;
        if(hivemind.getMineProcess().isActive())return hivemind.getMineProcess().targetsAncientDebris()?TaskLoadoutStore.Mode.NETHER:TaskLoadoutStore.Mode.MINE;
        if(hivemind.getPortalTravelProcess().isActive()||ctx.world()!=null&&MiningRouteAccess.dimension(ctx).equals("minecraft:the_nether"))return TaskLoadoutStore.Mode.NETHER;
        return TaskLoadoutStore.Mode.ROAM;
    }
    public String kitName(){return activeKit().name().toLowerCase(Locale.ROOT);}
    public TaskLoadoutStore kits(){ensureScope();if(kits==null||!ctx.player().getUUID().equals(loadoutAccount)){loadoutAccount=ctx.player().getUUID();kits=new TaskLoadoutStore(serverRoot,loadoutAccount);}return kits;}
    public ActivityJournal journal(){kits();if(journal==null)journal=new ActivityJournal(serverRoot,loadoutAccount);return journal;}
    public void reloadKits(){kits=null;}
    public void retrySupplies(){stockRetry.clear();nextSupply=nextDelivery=0;}
    public Map<String,Integer> kitTargets(TaskLoadoutStore.Mode mode){return kits().targets(mode,ExpeditionLoadout.targets(ctx),mode==TaskLoadoutStore.Mode.NETHER&&hivemind.getMineProcess().targetsAncientDebris(),BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx)),hivemind.getStalkProcess().combatStyle(),HiveMind.settings().botRestockBlocks.value);}
    private Map<String,Integer> kitTargets(){return kitTargets(activeKit());}
    public Map<String,Integer> currentKitTargets(){return kitTargets();}
    public Map<String,Integer> flightRefillTargets(){
        var targets=kitTargets();
        return ResupplyPolicy.flightTargets(targets,ExpeditionLoadout.counts(ctx,targets.keySet()),FlightPolicy.reserve(FlightPolicy.Mode.TRAVEL,HiveMind.settings().botFlightRocketReserve.value,HiveMind.settings().elytraMinFireworksBeforeLanding.value));
    }
    /** A critical carried refill must finish before another takeoff, including inside a normal supply cooldown. */
    public boolean prepareFlight(){
        if(outageReturn||recalling)return true;
        if(supplying)return false;
        if(!HiveMind.settings().botExpeditionSupplies.value||!HiveMind.settings().botAutoResupply.value)return true;
        var targets=flightRefillTargets();
        if(!hivemind.getShulkerProcess().hasPortableItems(targets,true))return true;
        if(busy()||hivemind.getDepositProcess().isActive())return false;
        if(stockRetry.ready(targets,ExpeditionLoadout.counts(ctx,targets.keySet()),System.currentTimeMillis()).isEmpty())return true;
        try{beginRestock(targets,true);}catch(RuntimeException error){abortBase("Carried flight refill: "+error.getMessage());}return false;
    }
    private void ensureScope(){var here=MiningRouteAccess.root(ctx);if(serverRoot==null){serverRoot=here;return;}if(!serverRoot.equals(here)){serverRoot=here;loadoutAccount=null;kits=null;journal=null;stockRetry.clear();obstacles.clear();onLostControl();nextSupply=0;walkUntil=0;blockerUntil=0;}}
    public boolean prepareTrip(BlockPos destination){
        long now=System.currentTimeMillis();if(outageReturn||recalling)return true;if(supplying||waitingDelivery!=null)return false;
        if(ctx.player()==null||ctx.world()==null||ctx.player().isCreative()||hivemind.isPlayerClient())return true;
        boolean prepare=destination.distSqr(ctx.playerFeet())>256*256||hivemind.getStalkProcess().hunting()||hivemind.getMineProcess().isActive();
        if(hivemind.getFlight().flying()||!prepare||now<nextSupply||!HiveMind.settings().botExpeditionSupplies.value||!HiveMind.settings().botAutoResupply.value||busy()||hivemind.getDepositProcess().isActive())return true;
        ensureScope();nextSupply=now+30000;
        var kit=kitTargets();var loose=ExpeditionLoadout.counts(ctx,kit.keySet());var boxed=ExpeditionLoadout.boxedCounts(ctx,kit.keySet());
        var plan=ResupplyPolicy.plan(kit,loose,boxed,hivemind.getStalkProcess().hunting());
        Map<String,Integer> targets=stockRetry.ready(plan.portable().isEmpty()?plan.storage():plan.portable(),loose,now);
        // Plan an attainable leg plus the next known resupply approach, without treating boxed stock as usable midair.
        double outward=Math.min(8192,Math.hypot((double)destination.getX()-ctx.playerFeet().getX(),(double)destination.getZ()-ctx.playerFeet().getZ()));
        double home=MiningRouteAccess.store(ctx).deposits().stream().filter(s->s.endpoint().dimension().equals(MiningRouteAccess.dimension(ctx))).mapToDouble(s->Math.hypot((double)s.endpoint().x()-destination.getX(),(double)s.endpoint().z()-destination.getZ())).min().orElse(outward);
        var estimate=hivemind.getReliability().budget().estimate(outward,Math.min(8192,home),HiveMind.settings().botFlightRocketReserve.value);
        int needed=Math.min(kit.getOrDefault("@rockets",0),estimate.rockets());
        if(needed>loose.getOrDefault("@rockets",0)+boxed.getOrDefault("@rockets",0)&&!stockRetry.ready(Map.of("@rockets",kit.getOrDefault("@rockets",0)),loose,now).isEmpty()){
            var expanded=new LinkedHashMap<>(targets);expanded.put("@rockets",kit.get("@rockets"));targets=Map.copyOf(expanded);
        }
        if(plan.portable().isEmpty()&&needed<=loose.getOrDefault("@rockets",0)+boxed.getOrDefault("@rockets",0))targets=ResupplyPolicy.plan(targets,loose,Map.of(),hivemind.getStalkProcess().hunting()).storage();
        if(targets.isEmpty())return true;
        if(plan.portable().isEmpty()&&requestDelivery(targets,now))return false;
        try{beginRestock(targets,!plan.portable().isEmpty());return false;}
        catch(RuntimeException error){stockRetry.finished(targets,loose,now);blocker="Supply check: "+error.getMessage();next="Continue with carried supplies; defer unchanged shortages for 10 minutes";nextSupply=now+60000;blockerUntil=nextSupply;return true;}
    }
    private boolean safeRestock(){return ctx.minecraft().gui.screen()==null&&ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty()&&hivemind.getShulkerProcess().safeForResupply();}
    private void abortBase(String reason){if(baseDestination!=null)hivemind.getReliability().routes().failed("base",baseDestination,reason,System.currentTimeMillis());stockRetry.finished(supplyTargets,ExpeditionLoadout.counts(ctx,supplyTargets.keySet()),System.currentTimeMillis());supplying=baseTrip=returningFromBase=false;baseDestination=supplyOrigin=null;blocker=reason;blockerUntil=nextSupply=System.currentTimeMillis()+60000;next="Continue original task; inspect the supply route";journal().note("Supply",reason);}
    public void requestRestock(){if(ctx.player()==null||ctx.world()==null||hivemind.isPlayerClient()||hivemind.getAutonomy().paused())throw new IllegalStateException("Join the world and resume the bot before resupplying");if(supplying||hivemind.getDepositProcess().isActive()||hivemind.getShulkerProcess().isActive())throw new IllegalStateException("A supply trip is already active");if(busy()||!hivemind.getShulkerProcess().safeGrounded())throw new IllegalStateException("Wait until the bot is grounded in a safe spot with closed containers");ensureScope();Map<String,Integer> targets=new LinkedHashMap<>(kitTargets());targets.entrySet().removeIf(e->SupplyReserves.refilled(e.getKey(),e.getValue(),ExpeditionLoadout.count(ctx,e.getKey())));if(targets.isEmpty())return;beginRestock(targets);}
    private void beginRestock(Map<String,Integer> targets){beginRestock(targets,false);}
    private void beginRestock(Map<String,Integer> targets,boolean carriedOnly){long now=System.currentTimeMillis();
        supplyPortableOnly=carriedOnly;supplyBefore=ExpeditionLoadout.counts(ctx,targets.keySet());supplyTargets=Map.copyOf(targets);
        BotBaseAccess.discoverEnderChests(ctx);BotBaseAccess.scan(ctx);supplyOrigin=baseDestination=null;baseTrip=returningFromBase=false;
        var configured=bestBase();boolean unknownBase=configured!=null&&MiningRouteAccess.store(ctx).deposits().stream().noneMatch(s->configured.contains(s.endpoint())||s.endpoint().dimension().equals(MiningRouteAccess.dimension(ctx))&&MiningRouteAccess.position(s.endpoint()).distSqr(ctx.playerFeet())<=64);
        try{if(unknownBase&&!carriedOnly&&!hivemind.getShulkerProcess().hasPortableItems(targets,true))throw new IllegalStateException("Travel to the registered base to discover its containers");if(carriedOnly)hivemind.getDepositProcess().startCarriedFetch(targets);else hivemind.getDepositProcess().startExpeditionFetch(targets);supplying=true;supplyDeadline=now+1800000;blocker="Restocking "+String.join(", ",targets.keySet());next="Use carried boxes first, then resume original work";}
        catch(RuntimeException error){var base=bestBase();if(carriedOnly||base==null)throw error;hivemind.getFlight().cancel();supplyOrigin=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());baseDestination=base.point();baseTrip=supplying=true;supplyDeadline=now+1800000;blocker="Returning to bot base for supplies";next="Scan nearby base containers, restock, then return to the interrupted task";}
    }
    private BotBaseStore.Base bestBase(){return BotBaseAccess.store(ctx).best(MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),base->hivemind.getReliability().routes().penalty("base",base.point(),System.currentTimeMillis()));}
    private boolean requestDelivery(Map<String,Integer> targets,long now){
        if(!HiveMind.settings().botRequestSupplyDelivery.value||now<nextDelivery||!hivemind.getShulkerProcess().safeGrounded())return false;
        var base=bestBase();if(base!=null&&base.point().dimension().equals(MiningRouteAccess.dimension(ctx))&&MiningRouteAccess.position(base.point()).distSqr(ctx.playerFeet())<256*256)return false;
        String item=targets.containsKey("@rockets")?"minecraft:firework_rocket":targets.containsKey("@food")?hivemind.getNetwork().deliveryFood():"";if(item.isBlank())return false;
        int amount=item.equals("minecraft:firework_rocket")?64:32;
        try{waitingDelivery=hivemind.getNetwork().requestSupplyDelivery(item,amount);deliveryItem=item;deliveryExpected=BotItems.supplyCount(ctx,item)+amount;deliveryDeadline=now+600000;nextDelivery=now+600000;blocker="Waiting for a teammate to deliver "+amount+" "+item;next="Verify recipient inventory, then resume the retained task";journal().note("Supply",blocker);return true;}catch(RuntimeException unavailable){nextDelivery=now+60000;return false;}
    }
    private boolean busy(){return hivemind.getCompanionProcess().awaitingDeathInventory()||ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty()||ctx.player().isUsingItem()||hivemind.getConflictProcess().isActive()||hivemind.getSurvivalProcess().isActive()||hivemind.getShulkerProcess().isActive()||hivemind.getCompanionProcess().isActive();}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||event.getType()!=TickEvent.Type.IN||ctx.player()==null||!ctx.player().isAlive())return;
        long now=System.currentTimeMillis();ensureScope();
        if(hivemind.getReliability().holding()||hivemind.getAutonomy().paused()||!BotWork.active(hivemind)||busy()){progress.sample("",0,0,false,now);return;}
        if(!supplying&&!hivemind.getDepositProcess().isActive()&&!hivemind.getFlight().flying()){
            BlockPos destination=hivemind.getScoutProcess().destination();if(destination==null)destination=hivemind.getRoamProcess().destination();if(destination==null&&hivemind.getStalkProcess().isActive())destination=hivemind.getStalkProcess().teamPoint();
            if(destination==null&&(hivemind.getStalkProcess().hunting()||hivemind.getMineProcess().isActive()))destination=ctx.playerFeet();
            if(destination!=null&&!prepareTrip(destination))return;
        }
        if(supplying||hivemind.getDepositProcess().isActive()||now<recoveryUntil||!HiveMind.settings().botStuckRecovery.value)return;
        var goal=hivemind.getPathingBehavior().getGoal();
        boolean paused=hivemind.getPathingControlManager().mostRecentCommand().map(command->command.commandType==PathingCommandType.REQUEST_PAUSE||command.commandType==PathingCommandType.SET_GOAL_AND_PAUSE).orElse(false);
        boolean journey=hivemind.getRoamProcess().destination()!=null||hivemind.getScoutProcess().isActive()||hivemind.getCustomGoalProcess().travelling();
        boolean moving=RecoveryPolicy.navigationPending(hivemind.getReliability().holding(),paused,hivemind.getFlight().flying(),journey,hivemind.getPathingBehavior().isPathing(),goal!=null,goal!=null&&goal.isInGoal(ctx.playerFeet()));
        RecoveryPolicy.Action action=progress.sample(goal==null?"":goal.toString(),ctx.player().getX(),ctx.player().getZ(),moving,now);if(action==RecoveryPolicy.Action.NONE)return;
        recoveries++;BlockPos failed=ctx.playerFeet().immutable();var who=hivemind.getNetwork().localIdentity();var obstacle=new OperationsPackets.Obstacle(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,failed),"Repeated navigation failure",now+120000,now);accept(obstacle,now);hivemind.getNetwork().publishOperation(obstacle);
        hivemind.getFlight().cancel();hivemind.getPathingBehavior().secretInternalSegmentCancel();hivemind.getInputOverrideHandler().clearAllKeys();
        sidestep=null;if(action!=RecoveryPolicy.Action.REPATH){for(int n=0;n<8;n++){double angle=(recoveries+n)*Math.PI/4;BlockPos p=failed.offset((int)Math.round(Math.cos(angle)*5),0,(int)Math.round(Math.sin(angle)*5));if(ctx.world().hasChunkAt(p)&&ctx.world().getWorldBorder().isWithinBounds(p)&&ctx.world().getBlockState(p).getCollisionShape(ctx.world(),p).isEmpty()&&ctx.world().getBlockState(p.above()).getCollisionShape(ctx.world(),p.above()).isEmpty()&&!ctx.world().getBlockState(p.below()).getCollisionShape(ctx.world(),p.below()).isEmpty()){sidestep=p;break;}}}
        if(action==RecoveryPolicy.Action.WALK){walkUntil=now+20000;groundRecoveryStarted=now;groundRecoveryOrigin=failed;}recoveryUntil=now+(sidestep==null?1000:5000);blockerUntil=now+15000;blocker="Navigation stalled: "+action.name().toLowerCase(Locale.ROOT);next=action==RecoveryPolicy.Action.WALK?"Walk away from failed launch area before retrying flight":sidestep==null?"Calculate a fresh route":"Take a short detour, then resume";journal().note("Navigation",blocker+" at "+failed+"; "+next);
    }
    @Override public void onLostControl(){recalling=false;recallBase=null;outageReturn=false;outageBase=null;waitingDelivery=null;baseTrip=returningFromBase=supplyPortableOnly=false;baseDestination=supplyOrigin=null;supplying=false;recoveryUntil=0;sidestep=null;supplyBefore=Map.of();}
    @Override public String displayName0(){return blocker;}
}

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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Temporary recovery and expedition preparation. Parent tasks remain owned and resume afterward. */
public final class OperationsProcess extends HiveMindProcessHelper implements IBehavior {
    private final RecoveryPolicy progress=new RecoveryPolicy();
    private final Map<String,OperationsPackets.Obstacle> obstacles=new ConcurrentHashMap<>();
    private BlockPos sidestep;
    private long recoveryUntil,walkUntil,nextSupply,supplyDeadline;
    private boolean supplying,baseTrip,returningFromBase,waitingPortable;
    private hivemind.utils.mining.WorldRoutes.Endpoint baseDestination,supplyOrigin;
    private long blockerUntil;
    private Map<String,Integer> supplyTargets=Map.of();
    private String blocker="",next="Continue current task";
    private int recoveries;
    private java.nio.file.Path serverRoot;
    private java.util.UUID loadoutAccount;
    private TaskLoadoutStore kits;
    private ActivityJournal journal;
    public OperationsProcess(HiveMind bot){super(bot);}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return .3;}
    @Override public boolean isActive(){return !hivemind.isPlayerClient()&&(supplying||System.currentTimeMillis()<recoveryUntil);}
    public boolean walking(){return System.currentTimeMillis()<walkUntil;}
    public String blocker(){return supplying||System.currentTimeMillis()<blockerUntil?blocker:"";}public String nextAction(){return supplying||System.currentTimeMillis()<blockerUntil?next:"Continue current task";}public int recoveries(){return recoveries;}
    private String key(OperationsPackets.Obstacle p){return p.point().dimension()+":"+p.point().x()+":"+p.point().y()+":"+p.point().z();}
    public void accept(OperationsPackets.Obstacle p,long now){if(!p.valid(now)||p.until()<now)return;obstacles.values().removeIf(o->o.until()<now);if(obstacles.size()<128||obstacles.containsKey(key(p)))obstacles.put(key(p),p);}
    public Map<Long,Double> penalties(String dimension){Map<Long,Double> costs=new HashMap<>();long now=System.currentTimeMillis();for(var o:obstacles.values())if(o.until()>now&&o.point().dimension().equals(dimension))for(int x=-2;x<=2;x++)for(int y=-3;y<=3;y++)for(int z=-2;z<=2;z++)costs.put(BlockPos.asLong(o.point().x()+x,o.point().y()+y,o.point().z()+z),120D);return Map.copyOf(costs);}
    public void warn(BlockPos point,String reason,long ttl){if(hivemind.isPlayerClient()||ctx.world()==null||ctx.player()==null)return;long now=System.currentTimeMillis();var who=hivemind.getNetwork().localIdentity();var warning=new OperationsPackets.Obstacle(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,point),reason,now+Math.max(1000,Math.min(600000,ttl)),now);String key=key(warning);var previous=obstacles.get(key);if(previous!=null&&now-previous.observedAt()<5000)return;accept(warning,now);hivemind.getNetwork().publishOperation(warning);}
    public boolean placementBlocked(BlockPos point){String dimension=MiningRouteAccess.dimension(ctx);long now=System.currentTimeMillis();return obstacles.values().stream().anyMatch(o->o.until()>now&&o.reason().startsWith("Placement")&&o.point().dimension().equals(dimension)&&o.point().x()==point.getX()&&o.point().y()==point.getY()&&o.point().z()==point.getZ());}
    public double penalty(String dimension,BlockPos point){long now=System.currentTimeMillis();return obstacles.values().stream().anyMatch(o->o.until()>now&&o.point().dimension().equals(dimension)&&Math.abs(point.getX()-o.point().x())<=2&&Math.abs(point.getY()-o.point().y())<=3&&Math.abs(point.getZ()-o.point().z())<=2)?120:0;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        long now=System.currentTimeMillis();
        if(hivemind.getConflictProcess().isActive()||hivemind.getClutch().active()||hivemind.getStalkProcess().committedCombat())return new PathingCommand(null,PathingCommandType.DEFER);
        if(supplying&&waitingPortable){
            if(now>=supplyDeadline){waitingPortable=false;abortBase("Could not find a quiet spot for portable resupply");return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(!safeRestock())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
            waitingPortable=false;supplying=false;try{beginRestock(supplyTargets);}catch(RuntimeException error){abortBase("Portable stock unavailable: "+error.getMessage());}return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
        }
        if(supplying&&baseTrip){
            if(now>=supplyDeadline){abortBase("Bot base trip timed out");return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            var destination=returningFromBase?supplyOrigin:baseDestination;
            if(!destination.dimension().equals(MiningRouteAccess.dimension(ctx))){try{if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(destination.dimension(),MiningRouteAccess.position(destination));return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException error){abortBase("Base route unavailable: "+error.getMessage());return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}}
            GoalNear goal=new GoalNear(MiningRouteAccess.position(destination),3);if(!goal.isInGoal(ctx.playerFeet()))return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
            if(returningFromBase){baseTrip=returningFromBase=supplying=false;nextSupply=now+60000;next="Resume original task";return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
            if(!safeRestock())return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
            BotBaseAccess.scan(ctx);baseTrip=false;
            try{hivemind.getDepositProcess().startExpeditionFetch(supplyTargets);blocker="Restocking from bot base containers";return new PathingCommand(null,PathingCommandType.DEFER);}catch(RuntimeException error){blocker="Base stock unavailable: "+error.getMessage();baseTrip=returningFromBase=true;blockerUntil=now+60000;return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        }
        if(supplying){if(hivemind.getDepositProcess().isActive()&&now>=supplyDeadline){hivemind.getDepositProcess().onLostControl();blocker="Supply trip timed out";}if(hivemind.getDepositProcess().isActive()&&now<supplyDeadline)return new PathingCommand(null,PathingCommandType.DEFER);supplying=false;String error=hivemind.getDepositProcess().lastFetchError();if(error==null&&supplyTargets.entrySet().stream().anyMatch(e->!SupplyReserves.refilled(e.getKey(),e.getValue(),ExpeditionLoadout.count(ctx,e.getKey()))))error="registered stock did not contain all requested supplies";blocker=error==null?"":"Supplies unavailable: "+error;next=error==null?"Resume original task":"Continue with current supplies; retry stock in 60 seconds";nextSupply=now+(error==null?10000:60000);blockerUntil=nextSupply;if(supplyOrigin!=null&&!MiningRouteAccess.endpoint(ctx,ctx.playerFeet()).equals(supplyOrigin)){baseTrip=returningFromBase=supplying=true;}return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        if(sidestep!=null&&now<recoveryUntil&&!failed)return new PathingCommand(new GoalNear(sidestep,1),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
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
    public Map<String,Integer> kitTargets(TaskLoadoutStore.Mode mode){return kits().targets(mode,ExpeditionLoadout.targets(ctx),mode==TaskLoadoutStore.Mode.NETHER&&hivemind.getMineProcess().targetsAncientDebris(),BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx)),hivemind.getStalkProcess().combatStyle(),HiveMind.settings().botRestockBlocks.value);}
    private Map<String,Integer> kitTargets(){return kitTargets(activeKit());}
    private Map<String,Integer> supplies(){
        Map<String,Integer> targets=new LinkedHashMap<>(kitTargets());
        targets.entrySet().removeIf(e->ExpeditionLoadout.count(ctx,e.getKey())>=SupplyReserves.minimum(e.getKey(),e.getValue()));
        return targets;
    }
    private void ensureScope(){var here=MiningRouteAccess.root(ctx);if(serverRoot==null){serverRoot=here;return;}if(!serverRoot.equals(here)){serverRoot=here;loadoutAccount=null;kits=null;journal=null;obstacles.clear();onLostControl();nextSupply=0;walkUntil=0;blockerUntil=0;}}
    public boolean prepareTrip(BlockPos destination){
        long now=System.currentTimeMillis();if(supplying)return false;
        if(ctx.player()==null||ctx.world()==null||ctx.player().isCreative()||hivemind.isPlayerClient())return true;
        boolean prepare=destination.distSqr(ctx.playerFeet())>256*256||hivemind.getStalkProcess().hunting()||hivemind.getMineProcess().isActive();
        if(hivemind.getFlight().flying()||!prepare||now<nextSupply||!HiveMind.settings().botExpeditionSupplies.value||!HiveMind.settings().botAutoResupply.value||busy()||hivemind.getDepositProcess().isActive())return true;
        ensureScope();nextSupply=now+30000;Map<String,Integer> targets=supplies();if(targets.isEmpty())return true;
        try{beginRestock(targets);return false;}
        catch(RuntimeException error){blocker="Supply check: "+error.getMessage();next="Continue with available supplies; retry storage in 60 seconds";nextSupply=now+60000;blockerUntil=nextSupply;return true;}
    }
    private boolean safeRestock(){return ctx.minecraft().gui.screen()==null&&ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty()&&hivemind.getShulkerProcess().safeForResupply();}
    private void abortBase(String reason){supplying=baseTrip=returningFromBase=false;baseDestination=supplyOrigin=null;blocker=reason;blockerUntil=nextSupply=System.currentTimeMillis()+60000;next="Continue original task; inspect the base route";journal().note("Supply",reason);}
    public void requestRestock(){if(ctx.player()==null||ctx.world()==null||hivemind.isPlayerClient()||hivemind.getAutonomy().paused())throw new IllegalStateException("Join the world and resume the bot before resupplying");if(supplying||hivemind.getDepositProcess().isActive()||hivemind.getShulkerProcess().isActive())throw new IllegalStateException("A supply trip is already active");if(busy()||!hivemind.getShulkerProcess().safeGrounded())throw new IllegalStateException("Wait until the bot is grounded in a safe spot with closed containers");ensureScope();Map<String,Integer> targets=new LinkedHashMap<>(kitTargets());targets.entrySet().removeIf(e->SupplyReserves.refilled(e.getKey(),e.getValue(),ExpeditionLoadout.count(ctx,e.getKey())));if(targets.isEmpty())return;beginRestock(targets);}
    private void beginRestock(Map<String,Integer> targets){long now=System.currentTimeMillis();
        if(hivemind.getShulkerProcess().hasPortableReserves()&&!safeRestock()){hivemind.getFlight().cancel();supplyTargets=Map.copyOf(targets);supplying=waitingPortable=true;supplyDeadline=now+60000;blocker="Waiting for a quiet spot to open carried shulkers";next="Fill reserves from carried boxes before leaving";return;}
        BotBaseAccess.discoverEnderChests(ctx);BotBaseAccess.scan(ctx);supplyOrigin=baseDestination=null;baseTrip=returningFromBase=false;
        var configured=BotBaseAccess.store(ctx).get();boolean unknownBase=configured!=null&&MiningRouteAccess.store(ctx).deposits().stream().noneMatch(s->configured.contains(s.endpoint())||s.endpoint().dimension().equals(MiningRouteAccess.dimension(ctx))&&MiningRouteAccess.position(s.endpoint()).distSqr(ctx.playerFeet())<=64);
        try{if(unknownBase&&!hivemind.getShulkerProcess().hasPortableReserves())throw new IllegalStateException("Travel to the registered base to discover its containers");hivemind.getDepositProcess().startExpeditionFetch(targets);supplyTargets=targets;supplying=true;supplyDeadline=now+1800000;blocker="Restocking "+String.join(", ",targets.keySet());next="Resume original task after filling carried reserves";}
        catch(RuntimeException error){var base=BotBaseAccess.store(ctx).get();if(base==null)throw error;hivemind.getFlight().cancel();supplyOrigin=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());baseDestination=base.point();supplyTargets=Map.copyOf(targets);baseTrip=supplying=true;supplyDeadline=now+1800000;blocker="Returning to bot base for supplies";next="Scan nearby base containers, restock, then return to the interrupted task";}
    }
    private boolean busy(){return hivemind.getCompanionProcess().awaitingDeathInventory()||ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty()||ctx.player().isUsingItem()||hivemind.getConflictProcess().isActive()||hivemind.getSurvivalProcess().isActive()||hivemind.getShulkerProcess().isActive()||hivemind.getCompanionProcess().isActive();}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||event.getType()!=TickEvent.Type.IN||ctx.player()==null||!ctx.player().isAlive())return;
        long now=System.currentTimeMillis();ensureScope();
        if(hivemind.getAutonomy().paused()||!BotWork.active(hivemind)||busy()){progress.sample("",0,0,false,now);return;}
        if(!supplying&&!hivemind.getDepositProcess().isActive()&&!hivemind.getFlight().flying()){
            BlockPos destination=hivemind.getScoutProcess().destination();if(destination==null)destination=hivemind.getRoamProcess().destination();if(destination==null&&hivemind.getStalkProcess().isActive())destination=hivemind.getStalkProcess().teamPoint();
            if(destination==null&&(hivemind.getStalkProcess().hunting()||hivemind.getMineProcess().isActive()))destination=ctx.playerFeet();
            if(destination!=null&&!prepareTrip(destination))return;
        }
        if(supplying||hivemind.getDepositProcess().isActive()||now<recoveryUntil||!HiveMind.settings().botStuckRecovery.value)return;
        var goal=hivemind.getPathingBehavior().getGoal();boolean moving=(hivemind.getPathingBehavior().isPathing()||hivemind.getFlight().flying())&&goal!=null&&!goal.isInGoal(ctx.playerFeet());
        RecoveryPolicy.Action action=progress.sample(goal==null?"":goal.toString(),ctx.player().getX(),ctx.player().getZ(),moving,now);if(action==RecoveryPolicy.Action.NONE)return;
        recoveries++;BlockPos failed=ctx.playerFeet().immutable();var who=hivemind.getNetwork().localIdentity();var obstacle=new OperationsPackets.Obstacle(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,failed),"Repeated navigation failure",now+120000,now);accept(obstacle,now);hivemind.getNetwork().publishOperation(obstacle);
        hivemind.getFlight().cancel();hivemind.getPathingBehavior().secretInternalSegmentCancel();hivemind.getInputOverrideHandler().clearAllKeys();
        sidestep=null;if(action!=RecoveryPolicy.Action.REPATH){for(int n=0;n<8;n++){double angle=(recoveries+n)*Math.PI/4;BlockPos p=failed.offset((int)Math.round(Math.cos(angle)*5),0,(int)Math.round(Math.sin(angle)*5));if(ctx.world().hasChunkAt(p)&&ctx.world().getWorldBorder().isWithinBounds(p)&&ctx.world().getBlockState(p).getCollisionShape(ctx.world(),p).isEmpty()&&ctx.world().getBlockState(p.above()).getCollisionShape(ctx.world(),p.above()).isEmpty()&&!ctx.world().getBlockState(p.below()).getCollisionShape(ctx.world(),p.below()).isEmpty()){sidestep=p;break;}}}
        if(action==RecoveryPolicy.Action.WALK)walkUntil=now+120000;recoveryUntil=now+(sidestep==null?1000:5000);blockerUntil=now+15000;blocker="Navigation stalled: "+action.name().toLowerCase(Locale.ROOT);next=action==RecoveryPolicy.Action.WALK?"Walk away from failed launch area before retrying flight":sidestep==null?"Calculate a fresh route":"Take a short detour, then resume";journal().note("Navigation",blocker+" at "+failed+"; "+next);
    }
    @Override public void onLostControl(){baseTrip=returningFromBase=waitingPortable=false;baseDestination=supplyOrigin=null;supplying=false;recoveryUntil=0;sidestep=null;}
    @Override public String displayName0(){return blocker;}
}

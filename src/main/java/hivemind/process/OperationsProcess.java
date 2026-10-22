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
    private boolean supplying;
    private String blocker="",next="Continue current task";
    private int recoveries;
    private Object world;
    public OperationsProcess(HiveMind bot){super(bot);}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return .3;}
    @Override public boolean isActive(){return !hivemind.isPlayerClient()&&(supplying||System.currentTimeMillis()<recoveryUntil);}
    public boolean walking(){return System.currentTimeMillis()<walkUntil;}
    public String blocker(){return blocker;}public String nextAction(){return next;}public int recoveries(){return recoveries;}
    private String key(OperationsPackets.Obstacle p){return p.point().dimension()+":"+p.point().x()+":"+p.point().y()+":"+p.point().z();}
    public void accept(OperationsPackets.Obstacle p,long now){if(!p.valid(now)||p.until()<now)return;obstacles.values().removeIf(o->o.until()<now);if(obstacles.size()<128||obstacles.containsKey(key(p)))obstacles.put(key(p),p);}
    public Map<Long,Double> penalties(String dimension){Map<Long,Double> costs=new HashMap<>();long now=System.currentTimeMillis();for(var o:obstacles.values())if(o.until()>now&&o.point().dimension().equals(dimension))for(int x=-2;x<=2;x++)for(int y=-3;y<=3;y++)for(int z=-2;z<=2;z++)costs.put(BlockPos.asLong(o.point().x()+x,o.point().y()+y,o.point().z()+z),120D);return Map.copyOf(costs);}
    public double penalty(String dimension,BlockPos point){long now=System.currentTimeMillis();return obstacles.values().stream().anyMatch(o->o.until()>now&&o.point().dimension().equals(dimension)&&Math.abs(point.getX()-o.point().x())<=2&&Math.abs(point.getY()-o.point().y())<=3&&Math.abs(point.getZ()-o.point().z())<=2)?120:0;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        long now=System.currentTimeMillis();
        if(supplying){if(hivemind.getDepositProcess().isActive()&&now>=supplyDeadline){hivemind.getDepositProcess().onLostControl();blocker="Supply trip timed out";}if(hivemind.getDepositProcess().isActive()&&now<supplyDeadline)return new PathingCommand(null,PathingCommandType.DEFER);supplying=false;String error=hivemind.getDepositProcess().lastFetchError();blocker=error==null?"":"Supplies unavailable: "+error;next=error==null?"Resume original task":"Continue with current supplies; retry stock in 60 seconds";nextSupply=now+(error==null?10000:60000);return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);}
        if(sidestep!=null&&now<recoveryUntil&&!failed)return new PathingCommand(new GoalNear(sidestep,1),PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
    }
    private Map<String,Integer> supplies(){
        Map<String,Integer> targets=new LinkedHashMap<>();
        if(BotInventory.count(ctx,BotInventory::food)<Math.max(8,HiveMind.settings().botMinimumFood.value)){Item food=HiveMind.settings().botFoodItems.value.stream().findFirst().orElse(Items.COOKED_BEEF);targets.put(BuiltInRegistries.ITEM.getKey(food).toString(),Math.max(8,HiveMind.settings().botMinimumFood.value));}
        boolean wings=ctx.player().getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)||BotInventory.count(ctx,s->s.is(Items.ELYTRA))>0;
        int rockets=Math.max(16,Math.min(256,HiveMind.settings().botExpeditionRockets.value));if(wings&&BotInventory.count(ctx,s->s.is(Items.FIREWORK_ROCKET))<rockets)targets.put("minecraft:firework_rocket",rockets);
        int totems=Math.max(0,Math.min(8,HiveMind.settings().botExpeditionTotems.value));if(BotInventory.count(ctx,s->s.is(Items.TOTEM_OF_UNDYING))+(ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)?1:0)<totems)targets.put("minecraft:totem_of_undying",totems);
        if(HiveMind.settings().allowPlace.value&&BotInventory.count(ctx,BotInventory::building)<Math.max(32,HiveMind.settings().botMinimumBlocks.value))targets.put("minecraft:cobblestone",Math.max(32,HiveMind.settings().botMinimumBlocks.value));
        for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){var worn=ctx.player().getItemBySlot(slot);if(worn.isEmpty()||!worn.isDamageableItem()||worn.is(Items.ELYTRA)||worn.getMaxDamage()-worn.getDamageValue()>Math.max(10,worn.getMaxDamage()/6))continue;
            int spare=BotInventory.slot(ctx,s->s.is(worn.getItem())&&s.getMaxDamage()-s.getDamageValue()>s.getMaxDamage()/3);
            if(spare>=0){int source=spare<9?spare+36:spare,armor=switch(slot){case HEAD->5;case CHEST->6;case LEGS->7;default->8;};if(spare>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,8,ContainerInput.SWAP,ctx.player());ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,armor,spare<9?spare:8,ContainerInput.SWAP,ctx.player());if(spare>=9)ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,8,ContainerInput.SWAP,ctx.player());break;}
            targets.put(BuiltInRegistries.ITEM.getKey(worn.getItem()).toString(),BotInventory.count(ctx,s->s.is(worn.getItem()))+1);
        }
        return targets;
    }
    public boolean prepareTrip(BlockPos destination){
        long now=System.currentTimeMillis();if(supplying)return false;
        if(ctx.player()==null||ctx.world()==null||ctx.player().isCreative()||hivemind.isPlayerClient()||hivemind.getFlight().flying()||destination.distSqr(ctx.playerFeet())<=256*256||now<nextSupply||!HiveMind.settings().botExpeditionSupplies.value||!HiveMind.settings().botAutoResupply.value||busy()||hivemind.getDepositProcess().isActive())return true;
        nextSupply=now+30000;Map<String,Integer> targets=supplies();if(targets.isEmpty())return true;
        try{hivemind.getDepositProcess().startFetch(targets);supplying=true;supplyDeadline=now+900000;blocker="Restocking "+String.join(", ",targets.keySet());next="Return to departure point, then resume the original task";return false;}
        catch(RuntimeException error){blocker="Supply check: "+error.getMessage();next="Continue with available supplies; retry storage in 60 seconds";nextSupply=now+60000;return true;}
    }
    private boolean busy(){return ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty()||ctx.player().isUsingItem()||hivemind.getConflictProcess().isActive()||hivemind.getSurvivalProcess().isActive()||hivemind.getShulkerProcess().isActive()||hivemind.getCompanionProcess().isActive();}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||event.getType()!=TickEvent.Type.IN||ctx.player()==null||!ctx.player().isAlive())return;
        long now=System.currentTimeMillis();if(world!=ctx.world()){world=ctx.world();obstacles.clear();onLostControl();nextSupply=0;walkUntil=0;}
        if(hivemind.getAutonomy().paused()||!BotWork.active(hivemind)||busy()){progress.sample("",0,0,false,now);return;}
        if(!supplying&&!hivemind.getDepositProcess().isActive()&&!hivemind.getFlight().flying()){
            BlockPos destination=hivemind.getScoutProcess().destination();if(destination==null)destination=hivemind.getRoamProcess().destination();if(destination==null&&hivemind.getStalkProcess().isActive())destination=hivemind.getStalkProcess().teamPoint();
            if(destination!=null&&!prepareTrip(destination))return;
        }
        if(supplying||hivemind.getDepositProcess().isActive()||now<recoveryUntil||!HiveMind.settings().botStuckRecovery.value)return;
        var goal=hivemind.getPathingBehavior().getGoal();boolean moving=(hivemind.getPathingBehavior().isPathing()||hivemind.getFlight().flying())&&goal!=null&&!goal.isInGoal(ctx.playerFeet());
        RecoveryPolicy.Action action=progress.sample(goal==null?"":goal.toString(),ctx.player().getX(),ctx.player().getZ(),moving,now);if(action==RecoveryPolicy.Action.NONE)return;
        recoveries++;BlockPos failed=ctx.playerFeet().immutable();var who=hivemind.getNetwork().localIdentity();var obstacle=new OperationsPackets.Obstacle(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,failed),"Repeated navigation failure",now+120000,now);accept(obstacle,now);hivemind.getNetwork().publishOperation(obstacle);
        hivemind.getFlight().cancel();hivemind.getPathingBehavior().secretInternalSegmentCancel();hivemind.getInputOverrideHandler().clearAllKeys();
        sidestep=null;if(action!=RecoveryPolicy.Action.REPATH){for(int n=0;n<8;n++){double angle=(recoveries+n)*Math.PI/4;BlockPos p=failed.offset((int)Math.round(Math.cos(angle)*5),0,(int)Math.round(Math.sin(angle)*5));if(ctx.world().hasChunkAt(p)&&ctx.world().getWorldBorder().isWithinBounds(p)&&ctx.world().getBlockState(p).getCollisionShape(ctx.world(),p).isEmpty()&&ctx.world().getBlockState(p.above()).getCollisionShape(ctx.world(),p.above()).isEmpty()&&!ctx.world().getBlockState(p.below()).getCollisionShape(ctx.world(),p.below()).isEmpty()){sidestep=p;break;}}}
        if(action==RecoveryPolicy.Action.WALK)walkUntil=now+120000;recoveryUntil=now+(sidestep==null?1000:5000);blocker="Navigation stalled: "+action.name().toLowerCase(Locale.ROOT);next=action==RecoveryPolicy.Action.WALK?"Walk away from failed launch area before retrying flight":sidestep==null?"Calculate a fresh route":"Take a short detour, then resume";
    }
    @Override public void onLostControl(){supplying=false;recoveryUntil=0;sidestep=null;}
    @Override public String displayName0(){return blocker;}
}

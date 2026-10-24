/* HiveMind, LGPL-3.0. */
package hivemind.behavior;
import hivemind.HiveMind;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.utils.bot.*;
import hivemind.utils.mining.MiningRouteAccess;
import java.util.*;

/** Idle maintenance uses configured stations and available ingredients; it never launches an open-ended gathering loop. */
public final class MaintenanceBehavior extends Behavior {
    private long next;
    private static final Map<String,String> COOK=Map.of("minecraft:beef","minecraft:cooked_beef","minecraft:porkchop","minecraft:cooked_porkchop","minecraft:chicken","minecraft:cooked_chicken","minecraft:mutton","minecraft:cooked_mutton","minecraft:cod","minecraft:cooked_cod","minecraft:salmon","minecraft:cooked_salmon","minecraft:rabbit","minecraft:cooked_rabbit");
    public MaintenanceBehavior(HiveMind bot){super(bot);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE||event.getType()!=TickEvent.Type.IN||ctx.player()==null||ctx.world()==null||hivemind.isPlayerClient()||System.currentTimeMillis()<next)return;next=System.currentTimeMillis()+30000;
        if(!HiveMind.settings().botAutomaticMaintenance.value||hivemind.getReliability().holding()||hivemind.getAutonomy().paused()||BotWork.foreground(hivemind)||hivemind.getJobManager().hasQueuedJobs()||hivemind.getAutonomy().order()!=null||hivemind.getFlight().flying()||!hivemind.getShulkerProcess().safeForResupply())return;
        try{
            if(hivemind.getRepairProcess().tryAutomatic())return;
            var stations=MiningRouteAccess.locations(ctx);var stock=BotItems.inventory(ctx);int rockets=ExpeditionLoadout.count(ctx,"@rockets");var targets=hivemind.getOperations().currentKitTargets();int rocketTarget=targets.getOrDefault("@rockets",0),foodTarget=targets.getOrDefault("@food",0);
            if(rockets<ResupplyPolicy.minimum("@rockets",rocketTarget)&&stations.station("crafting")!=null){int batches=Math.min(stock.getOrDefault("minecraft:paper",0),stock.getOrDefault("minecraft:gunpowder",0));if(batches>0&&produce("minecraft:firework_rocket",Math.min(rocketTarget,rockets+batches*3)))return;}
            if(ExpeditionLoadout.count(ctx,"@food")<ResupplyPolicy.minimum("@food",foodTarget)&&stations.station("furnace")!=null)for(var pair:COOK.entrySet()){int raw=stock.getOrDefault(pair.getKey(),0);if(raw>0&&produce(pair.getValue(),Math.min(foodTarget,BotItems.supplyCount(ctx,pair.getValue())+raw)))return;}
        }catch(RuntimeException error){hivemind.getOperations().journal().note("Maintenance","Deferred: "+error.getMessage());next=System.currentTimeMillis()+300000;}
    }
    private boolean produce(String item,int target){var plan=hivemind.getCompanionProcess().plan(item,target);if(plan.actions().isEmpty()||plan.actions().stream().anyMatch(a->a.method()==GatherPlanner.Method.ACQUIRE))return false;hivemind.getCompanionProcess().produceSupplies(item,target);hivemind.getOperations().journal().note("Maintenance","Producing carried supplies from available ingredients: "+item);return true;}
}

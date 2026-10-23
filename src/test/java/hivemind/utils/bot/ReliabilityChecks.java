/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.utils.reliability.*;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import hivemind.utils.network.*;

/** Behavioral scenarios: competing actions, interruptions, uncertain transfers and recovery after restart. */
public final class ReliabilityChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        AtomicLong now=new AtomicLong(1000);ActionArbiter a=new ActionArbiter(now::get);
        try(var scope=a.scope("storage",70)){check(a.allow(ActionArbiter.Channel.INVENTORY,ActionArbiter.Channel.HAND),"storage acquires transaction channels");}
        try(var scope=a.scope("equipment",10)){check(!a.allow(ActionArbiter.Channel.INVENTORY),"equipment cannot swap underneath storage");}
        try(var scope=a.scope("combat",90)){check(a.allow(ActionArbiter.Channel.HAND,ActionArbiter.Channel.INVENTORY),"emergency preempts storage");}
        try(var scope=a.scope("storage",70)){check(!a.allow(ActionArbiter.Channel.HAND),"interrupted storage waits");}
        now.addAndGet(151);try(var scope=a.scope("storage",70)){check(a.allow(ActionArbiter.Channel.HAND),"expired ownership does not deadlock storage");}
        a.manual(true);try(var scope=a.scope("combat",100)){check(!a.allow(ActionArbiter.Channel.HAND),"player edition remains manual");}a.manual(false);
        Path root=Files.createTempDirectory("hivemind-reliability-");UUID account=UUID.randomUUID();
        TaskCheckpoint task=new TaskCheckpoint(root,account);task.start("mine 64 diamond_ore");long generation=task.generation();
        task.observed(List.of(new TaskCheckpoint.Interruption("combat","attacked","safe again",1000),new TaskCheckpoint.Interruption("storage","refilling","transfer confirmed",1000)));
        check(task.intent().startsWith("mine")&&task.stack().size()==2,"nested interruption retains original work");task.hold(true);
        TaskCheckpoint restored=new TaskCheckpoint(root,account);check(restored.held()&&restored.generation()>generation&&restored.stack().size()==2,"hold and interruption survive restart");
        restored.cancel();check(new TaskCheckpoint(root,account).intent().isEmpty(),"cancel cannot resurrect old intent");
        InventoryTransaction tx=new InventoryTransaction(root,account);tx.before("storage","withdraw rockets",3,"before",1000);
        tx=new InventoryTransaction(root,account);check(tx.pending()!=null,"uncertain inventory click survives restart");tx.confirmed(4,"after",true);check(tx.pending()!=null,"unrelated container cannot confirm transfer");
        tx.before("combat","equip totem",0,"current",1100);check(tx.pending().owner().equals("combat"),"emergency is journalled without replaying old transfer");
        tx.reconciled("actual server inventory",false);check(tx.pending()!=null,"cursor item prevents reconciliation");tx.reconciled("actual server inventory",true);check(tx.pending()==null,"actual synchronized inventory resolves interruption");
        FailureRecovery retries=new FailureRecovery();var first=retries.failed("chest",FailureRecovery.Kind.SERVER_REJECTED,0,"protected",1000);var second=retries.failed("chest",FailureRecovery.Kind.SERVER_REJECTED,0,"protected",2000);var third=retries.failed("chest",FailureRecovery.Kind.SERVER_REJECTED,0,"protected",3000);
        check(third.strategy()==FailureRecovery.Strategy.ALTERNATE&&third.retryAt()>second.retryAt(),"rejected source changes approach and backs off");check(!retries.ready("chest",0,3001)&&retries.ready("chest",1,3001),"confirmed progress permits earlier retry");
        RouteHealth routes=new RouteHealth(root);Endpoint point=new Endpoint("minecraft:overworld",10,64,20);routes.failed("storage",point,"blocked",1000);
        check(!new RouteHealth(root).available("storage",point,1001),"failed storage memory survives restart");routes.success("storage",point,2000);check(routes.available("storage",point,2001),"confirmed success clears failure penalty");
        TripBudget budget=new TripBudget();check(budget.estimate(8000,8000,16).rockets()>budget.estimate(1000,1000,16).rockets(),"longer trips budget more supplies");budget.observe(320,4,4);check(budget.metresPerRocket()<160,"budget learns higher observed consumption");
        RuntimeHealth health=new RuntimeHealth();health.tick(1000);health.tick(1050);for(int i=0;i<4;i++)health.correction(1100+i);check(health.poorFlightConditions(),"repeated server corrections lower flight confidence");
        CommandLedger ledger=new CommandLedger(root,account);UUID source=UUID.randomUUID(),older=UUID.randomUUID(),stop=UUID.randomUUID();ledger.accept(source,older,1000);ledger.accept(source,stop,2000);ledger=new CommandLedger(root,account);check(ledger.check(source,older,1000)==CommandLedger.Verdict.DUPLICATE,"restart cannot repeat an accepted order");check(ledger.check(source,UUID.randomUUID(),1500)==CommandLedger.Verdict.STALE,"late work cannot override a newer stop");
        var who=new NetworkProtocol.Identity(account,"BotOne",NetworkProtocol.scope("test"));UUID other=UUID.randomUUID();var peer=new NetworkProtocol.Identity(other,"BotTwo",who.scope());Teamwork team=new Teamwork();
        team.accept(new TeamPackets.Intent(account,"BotOne",TeamPackets.Kind.STORAGE,null,point,point,false,false,10000,Map.of("@rockets",128)),10000);team.accept(new TeamPackets.Intent(other,"BotTwo",TeamPackets.Kind.STORAGE,null,point,point,false,false,10000,Map.of("@rockets",64)),10000);
        var plan=team.plan(who,List.of(peer),10000);check(plan.leases().size()==1,"two bots cannot withdraw the same chest stock simultaneously");check(((TeamPackets.Plan)NetworkProtocol.decode(NetworkProtocol.encode(plan))).intents().stream().allMatch(i->i.supplies().containsKey("@rockets")),"stock quantities survive authenticated plan encoding");
        RegionRules regions=new RegionRules(root);regions.put(new RegionRules.Region("home",RegionRules.Kind.PROTECT,point,16));check(RegionRules.protectedAt(new RegionRules(root).regions(),point.dimension(),10,20),"explicit protection survives restart");check(!RegionRules.protectedAt(regions.regions(),"minecraft:the_nether",10,20),"regions never leak across dimensions");
        ShulkerManifest manifest=new ShulkerManifest(root,account);manifest.observe(List.of(new ShulkerManifest.Box(0,"minecraft:shulker_box","unknown",false,Map.of())),10000);check(manifest.boxes().get(0).contents().isEmpty()&&!manifest.boxes().get(0).known(),"unknown box contents are not invented");manifest.inspected("unknown",10000);check(!new ShulkerManifest(root,account).shouldInspect("unknown",10001),"empty unknown box is not reopened in a loop");
        Path durable=root.resolve("durable.properties");Properties one=new Properties();one.setProperty("value","good");AtomicProperties.write(durable,one);Properties two=new Properties();two.setProperty("value","new");AtomicProperties.write(durable,two);Files.writeString(durable,"bad=\\uBADZ");check(AtomicProperties.read(durable).getProperty("value").equals("good"),"malformed state recovers from the prior snapshot");
        System.out.println("Reliability ownership, restart and transaction scenarios passed");
    }
}

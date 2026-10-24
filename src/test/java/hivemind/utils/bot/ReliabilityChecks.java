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
        FlightPlan flight=new FlightPlan();var flightPoint=new FlightPolicy.Point(1000,128,1000);flight.begin(flightPoint,1000);flight.airborne(2000);check(!flight.refillReady(2100,200,16)&&flight.refillReady(2100,2,16)&&flight.refillReady(7000,200,16),"flight holds ordinary refill decisions but permits urgent landing");flight.landing("Confirmed destination");check(!flight.mayRetarget(new FlightPolicy.Point(2000,128,2000),8000),"landing cannot be undone by a noisy target update");
        RuntimeHealth health=new RuntimeHealth();health.tick(1000);health.tick(1050);for(int i=0;i<4;i++)health.correction(1100+i);check(health.poorFlightConditions(),"repeated server corrections lower flight confidence");
        CommandLedger ledger=new CommandLedger(root,account);UUID source=UUID.randomUUID(),older=UUID.randomUUID(),stop=UUID.randomUUID();ledger.accept(source,older,1000);ledger.accept(source,stop,2000);ledger=new CommandLedger(root,account);check(ledger.check(source,older,1000)==CommandLedger.Verdict.DUPLICATE,"restart cannot repeat an accepted order");check(ledger.check(source,UUID.randomUUID(),1500)==CommandLedger.Verdict.STALE,"late work cannot override a newer stop");
        var who=new NetworkProtocol.Identity(account,"BotOne",NetworkProtocol.scope("test"));UUID other=UUID.randomUUID();var peer=new NetworkProtocol.Identity(other,"BotTwo",who.scope());Teamwork team=new Teamwork();
        team.accept(new TeamPackets.Intent(account,"BotOne",TeamPackets.Kind.STORAGE,null,point,point,false,false,10000,Map.of("@rockets",128)),10000);team.accept(new TeamPackets.Intent(other,"BotTwo",TeamPackets.Kind.STORAGE,null,point,point,false,false,10000,Map.of("@rockets",64)),10000);
        var plan=team.plan(who,List.of(peer),10000);check(plan.leases().size()==1,"two bots cannot withdraw the same chest stock simultaneously");check(((TeamPackets.Plan)NetworkProtocol.decode(NetworkProtocol.encode(plan))).intents().stream().allMatch(i->i.supplies().containsKey("@rockets")),"stock quantities survive authenticated plan encoding");
        RegionRules regions=new RegionRules(root);regions.put(new RegionRules.Region("home",RegionRules.Kind.PROTECT,point,16));check(RegionRules.protectedAt(new RegionRules(root).regions(),point.dimension(),10,20),"explicit protection survives restart");check(!RegionRules.protectedAt(regions.regions(),"minecraft:the_nether",10,20),"regions never leak across dimensions");
        ShulkerManifest manifest=new ShulkerManifest(root,account);manifest.observe(List.of(new ShulkerManifest.Box(0,"minecraft:shulker_box","unknown",false,Map.of())),10000);check(manifest.boxes().get(0).contents().isEmpty()&&!manifest.boxes().get(0).known(),"unknown box contents are not invented");manifest.inspected("unknown",10000);check(!new ShulkerManifest(root,account).shouldInspect("unknown",10001),"empty unknown box is not reopened in a loop");
        Path durable=root.resolve("durable.properties");Properties one=new Properties();one.setProperty("value","good");AtomicProperties.write(durable,one);Properties two=new Properties();two.setProperty("value","new");AtomicProperties.write(durable,two);Files.writeString(durable,"bad=\\uBADZ");check(AtomicProperties.read(durable).getProperty("value").equals("good"),"malformed state recovers from the prior snapshot");
        restored.start("goto 100 64 200");restored.recall(true);restored.hold(true);restored.fleetRevision(7);restored=new TaskCheckpoint(root,account);check(restored.recall()&&restored.held()&&restored.intent().startsWith("goto")&&restored.fleetRevision()==7,"recall retains intent and its authority revision after restart");
        UUID order=UUID.randomUUID(),recall=UUID.randomUUID();OrderLifecycle lifecycle=new OrderLifecycle(root,account);lifecycle.accepted(order,other,"goto 100 64 200");lifecycle.interrupt(recall,other,"recall");lifecycle=new OrderLifecycle(root,account);check(lifecycle.suspended().id().equals(order),"recall preserves original acknowledgement identity");lifecycle.update(ControlPackets.Stage.COMPLETED,"Reached base");check(lifecycle.restore()&&lifecycle.active().id().equals(order),"resume restores original order rather than marking it cancelled");
        ReceiptJournal receipts=new ReceiptJournal(root,account);receipts.put(new ControlPackets.Receipt(account,"BotOne",order,other,true,"Confirmed",1000,ControlPackets.Stage.COMPLETED));receipts.put(new ControlPackets.Receipt(account,"BotOne",order,other,true,"Accepted",2000,ControlPackets.Stage.APPLIED));check(new ReceiptJournal(root,account).get(order).stage()==ControlPackets.Stage.COMPLETED,"application receipt cannot overwrite proven completion");check(ControlRules.mutatesTask("job pause")&&ControlRules.mutatesTask("scout clear")&&!ControlRules.mutatesTask("job list"),"queue edits participate in task ordering but queries do not");
        DeliveryCheckpoint delivery=new DeliveryCheckpoint(root,account);delivery.start(other,"BotTwo","minecraft:firework_rocket",128,point);delivery.dropping(64);delivery=new DeliveryCheckpoint(root,account);check(delivery.uncertain(),"crash after throw retains uncertain handoff");try{delivery.start(other,"BotTwo","minecraft:firework_rocket",128,point);throw new AssertionError("Unconfirmed stack was resent");}catch(IllegalStateException expected){}delivery.received(32);check(new DeliveryCheckpoint(root,account).value().pending()==32,"partial pickup proof persists");delivery.resolve("returned");check(delivery.value().delivered()==32&&!delivery.uncertain(),"returned stock gets no false delivery credit");delivery.dropping(64);delivery.received(64);check(new DeliveryCheckpoint(root,account).value().delivered()==96,"confirmed delivery progress survives restart");
        JobStore jobs=new JobStore(root);jobs.write(List.of(new JobStore.Job(1,64,List.of("minecraft:diamond_ore"),12,null,order,other)));var savedJob=new JobStore(root).read().get(0);check(savedJob.order().equals(order)&&savedJob.requester().equals(other)&&savedJob.deposited()==12,"queued order identity and actual deposit credit persist");
        soak(root,account);
        System.out.println("Reliability ownership, restart, recall, delivery and receipt scenarios passed");
    }
    private static void soak(Path root,UUID account){
        AtomicLong clock=new AtomicLong(1000);ActionArbiter actions=new ActionArbiter(clock::get);RuntimeHealth health=new RuntimeHealth();FailureRecovery failures=new FailureRecovery();
        TaskCheckpoint task=new TaskCheckpoint(root,account);task.start("explore");int preemptions=0,restarts=0;
        // Eight hours of deterministic policy ticks, not an assertion of eight hours of real gameplay.
        for(int tick=0;tick<8*60*60*20;tick++){
            clock.addAndGet(50);health.tick(clock.get());
            try(var storage=actions.scope("storage",70)){actions.allow(ActionArbiter.Channel.INVENTORY);}
            if(tick%97==0){try(var combat=actions.scope("combat",90)){check(actions.allow(ActionArbiter.Channel.INVENTORY),"combat recovery must not deadlock");preemptions++;}try(var storage=actions.scope("storage",70)){check(!actions.allow(ActionArbiter.Channel.INVENTORY),"preempted inventory owner resumed too early");}}
            if(tick%1200==0){var decision=failures.failed("route",FailureRecovery.Kind.PATH,tick/1200,"injected obstruction",clock.get());check(failures.ready("route",tick/1200+1,clock.get()),"new confirmed progress must reset route backoff");}
            if(tick%72000==71999){task.hold(true);task=new TaskCheckpoint(root,account);check(task.held()&&task.intent().equals("explore"),"restart lost original task or operator hold");task.hold(false);actions.reset();restarts++;}
        }
        check(preemptions>5000&&restarts==8&&!health.poorFlightConditions(),"long-run policy state drifted");
        System.out.println("Simulated eight-hour policy soak passed: 576000 ticks, "+preemptions+" combat interruptions, "+restarts+" checkpoint restarts");
    }

}

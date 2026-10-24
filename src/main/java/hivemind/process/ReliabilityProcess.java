/* HiveMind, LGPL-3.0. */
package hivemind.process;

import hivemind.HiveMind;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.events.*;
import hivemind.api.event.events.type.EventState;
import hivemind.api.process.*;
import hivemind.utils.HiveMindProcessHelper;
import hivemind.utils.bot.*;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.reliability.*;
import hivemind.utils.network.ControlRules;
import hivemind.command.manager.CommandManager;
import hivemind.command.argument.ArgConsumer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates interruptions and recovery without replacing the existing task implementations. */
public final class ReliabilityProcess extends HiveMindProcessHelper implements IBehavior {
    private Path root;
    private UUID account;
    private TaskCheckpoint task;
    private InventoryTransaction inventory;
    private RouteHealth routes;
    private RegionRules regions;
    private ShulkerManifest manifest;
    private DeathCircuit deathCircuit;
    private DeliveryCheckpoint delivery;
    private final RuntimeHealth health=new RuntimeHealth();
    private final FailureRecovery failures=new FailureRecovery();
    private final TripBudget budget=new TripBudget();
    private final Deque<Long> deaths=new ArrayDeque<>();
    private final Deque<String> trace=new ArrayDeque<>();
    private String lastTrace="";
    private Endpoint deathArea;
    private long deathHold,nextSample,nextHeartbeat,lastProgress;
    private double lastX,lastZ;
    private boolean controllerSeen,offlineHold,inventoryNeedsReconcile;
    private boolean returnedForOutage;
    private boolean updateHold;
    public void updateHold(boolean held){updateHold=held;}
    public boolean finishingInventory(){ensure();return updateHold||task!=null&&task.recall();}
    public boolean canResumeAfterRestart(){ensure();if(task==null||task.intent().isBlank())return true;String label=task.intent().replaceFirst("^#","").trim().split("\\s+",2)[0];return Set.of("mine","goto","path","follow","explore","gather","repair","recover","assist","deliver","escort","scout","stalk","kill","job").contains(label);}
    private long controllerLost;
    private long nextRestore;
    private boolean restoring;
    private String resumeBlocker="";
    private String offlinePolicy="continue",lastFailure="",nextRecovery="";
    private volatile boolean writingHeartbeat;
    private final ExecutorService disk=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"HiveMind-runtime-health");t.setDaemon(true);return t;});
    public ReliabilityProcess(HiveMind bot){super(bot);}
    private boolean online(){return ctx.player()!=null&&ctx.world()!=null;}
    public void ensure(){
        if(!online())return;
        Path here=MiningRouteAccess.root(ctx);UUID who=ctx.player().getUUID();if(here.equals(root)&&who.equals(account))return;
        root=here;account=who;task=new TaskCheckpoint(root,account);inventory=new InventoryTransaction(root,account);routes=new RouteHealth(root);regions=new RegionRules(root);manifest=new ShulkerManifest(root,account);deathCircuit=new DeathCircuit(root,account);delivery=new DeliveryCheckpoint(root,account);
        inventoryNeedsReconcile=inventory.pending()!=null;controllerSeen=false;controllerLost=0;offlineHold=false;deathHold=deathCircuit.until();deaths.clear();deathArea=null;
        lastX=ctx.player().getX();lastZ=ctx.player().getZ();lastProgress=System.currentTimeMillis();
        nextRestore=lastProgress+5000;resumeBlocker="";
    }
    public TaskCheckpoint checkpoint(){ensure();return task;}
    public RouteHealth routes(){ensure();return routes;}
    public RegionRules regions(){ensure();return regions;}
    public ShulkerManifest manifest(){ensure();return manifest;}
    public static String boxSignature(net.minecraft.world.item.ItemStack stack){return BotItems.id(stack)+"-"+Integer.toUnsignedString(stack.getComponents().hashCode(),16);}
    public DeliveryCheckpoint delivery(){ensure();return delivery;}
    public RuntimeHealth health(){return health;}
    public void outageAtBase(){returnedForOutage=true;offlineHold=true;}
    public List<String> trace(){return List.copyOf(trace);}
    public TripBudget budget(){return budget;}
    public void offlinePolicy(String value){if(!Set.of("continue","hold","return").contains(value))throw new IllegalArgumentException("Use continue, hold or return");offlinePolicy=value;}
    public boolean holding(){ensure();return task!=null&&(updateHold||task.held()||task.recall()||System.currentTimeMillis()<deathHold||offlineHold);}
    @Override public boolean isActive(){return !hivemind.isPlayerClient()&&online()&&holding();}
    @Override public boolean isTemporary(){return true;}
    @Override public double priority(){return 5;}
    @Override public PathingCommand onTick(boolean failed,boolean safe){
        if(hivemind.getClutch().active()||hivemind.getConflictProcess().isActive()||hivemind.getSurvivalProcess().isActive()||hivemind.getFlight().flying())return new PathingCommand(null,PathingCommandType.DEFER);
        if((updateHold||task.recall())&&(hivemind.getOperations().recalling()||hivemind.getPortalTravelProcess().isActive()||hivemind.getShulkerProcess().isActive()||hivemind.getDepositProcess().isActive()||hivemind.getWorkstationProcess().isActive()))return new PathingCommand(null,PathingCommandType.DEFER);
        hivemind.getInputOverrideHandler().clearAllKeys();return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE);
    }
    public void command(String command){
        if(restoring||hivemind.isPlayerClient()||!online())return;ensure();String[] words=command.trim().replaceFirst("^#","").split("\\s+",3);String label=words[0].toLowerCase(Locale.ROOT),first=words.length>1?words[1].toLowerCase(Locale.ROOT):"";
        if(Set.of("stop","cancel","forcecancel","c").contains(label)){hivemind.getOperations().cancelRecall();task.cancel();return;}
        if(label.equals("recall")){task.recall(true);task.hold(true);hivemind.getAutonomy().pause(true);return;}
        if(Set.of("pause","p","paws").contains(label)){task.hold(true);return;}
        if(Set.of("resume","r","unpause","unpaws").contains(label)){task.recall(false);hivemind.getOperations().cancelRecall();HiveMind.settings().botFleetRecall.value=false;HiveMind.settings().botFleetHold.value=false;task.hold(false);deathHold=0;deathCircuit.resume();offlineHold=false;return;}
        if(Set.of("stop","clear","cancel").contains(first)&&Set.of("stalk","kill","scout","job","deliver","escort","gather","repair","recover","assist").contains(label)){if(taskMatches(label))task.cancel(false);return;}
        if(Set.of("job","scout").contains(label)&&first.equals("pause")){if(taskMatches(label))task.hold(true);return;}
        if(label.equals("mine")&&first.matches("[1-9][0-9]*")&&!task.intent().isBlank())return;
        if(Set.of("status","list","history","sightings","report","supported","point","add","remove","set","reset","resolve","handoff").contains(first))return;
        if(ControlRules.workCommand(command)){HiveMind.settings().botFleetHold.value=false;HiveMind.settings().botFleetRecall.value=false;hivemind.getOperations().cancelRecall();task.start(command,MiningRouteAccess.dimension(ctx));resumeBlocker="";nextRestore=System.currentTimeMillis()+5000;hivemind.getAutonomy().pause(false);captureResume();if(label.equals("deposit")&&!hivemind.getDepositProcess().isActive()&&hivemind.getDepositProcess().lastDepositError()==null)task.completed("Inventory checked; no requested transferable items remain");}
    }
    private boolean taskMatches(String label){String saved=task.intent().replaceFirst("^#","").split("\\s+",2)[0];return saved.equals(label)||label.equals("job")&&saved.equals("mine");}
    public boolean select(int slot){if(ctx.player()==null||slot<0||slot>8||!hivemind.getActions().allow(ActionArbiter.Channel.HAND))return false;ctx.player().getInventory().setSelectedSlot(slot);return true;}
    public String fingerprint(){
        if(ctx.player()==null)return "offline";StringBuilder s=new StringBuilder();
        for(var stack:ctx.player().getInventory().getNonEquipmentItems())s.append(BotItems.id(stack)).append(':').append(stack.getCount()).append(':').append(stack.getComponents().hashCode()).append(';');
        for(var slot:net.minecraft.world.entity.EquipmentSlot.values()){var stack=ctx.player().getItemBySlot(slot);s.append(slot).append(':').append(BotItems.id(stack)).append(':').append(stack.getCount()).append(':').append(stack.getComponents().hashCode()).append(';');}
        return Integer.toHexString(s.toString().hashCode())+":"+s.length();
    }
    public boolean beforeClick(int menu,int slot,int button,String type){
        if(hivemind.isPlayerClient()||!hivemind.getActions().allow(ActionArbiter.Channel.INVENTORY,button==40?ActionArbiter.Channel.OFFHAND:ActionArbiter.Channel.HAND))return false;
        ensure();if(inventory==null)return false;var owner=hivemind.getActions().current();String name=owner==null?"command":owner.name();
        inventory.before(name,type+" slot "+slot,menu,fingerprint(),System.currentTimeMillis());return true;
    }
    public void inventoryConfirmed(int menu){
        if(hivemind.isPlayerClient()||!online())return;ensure();var p=inventory.pending();
        if(p!=null){health.confirmation(p.started(),System.currentTimeMillis());inventory.confirmed(menu,fingerprint(),ctx.player().containerMenu.getCarried().isEmpty());}
        if(inventoryNeedsReconcile&&menu==ctx.player().inventoryMenu.containerId){inventory.reconciled(fingerprint(),ctx.player().inventoryMenu.getCarried().isEmpty());inventoryNeedsReconcile=false;}
    }
    public FailureRecovery.Decision failed(String key,String reason,int progress){ensure();long now=System.currentTimeMillis();var d=failures.failed(key,FailureRecovery.classify(reason),progress,reason,now);lastFailure=d.kind()+": "+reason;nextRecovery=d.strategy()+" after "+Math.max(0,(d.retryAt()-now)/1000)+"s";health.recovery();return d;}
    public boolean retryReady(String key,int progress){return failures.ready(key,progress,System.currentTimeMillis());}
    public void success(String key,String proof){failures.success(key);lastProgress=System.currentTimeMillis();health.progress(lastProgress);if(online())hivemind.getOperations().journal().note("Confirmed",proof);}
    public void completed(String labels,String proof){ensure();if(task==null||task.intent().isBlank())return;String label=task.intent().trim().split("\\s+",2)[0].replaceFirst("^#","");if(Arrays.asList(labels.split(" ")).contains(label)){task.completed(proof);success("task",proof);}}
    public boolean recallArrived(){ensure();if(task==null||!task.recall()||hivemind.getOperations().recalling()||ctx.player()==null||!ctx.player().onGround())return false;var here=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());return BotBaseAccess.store(ctx).all().values().stream().anyMatch(base->base.point().dimension().equals(here.dimension())&&Math.hypot((double)base.point().x()-here.x(),(double)base.point().z()-here.z())<=3&&Math.abs(base.point().y()-here.y())<=3);}
    public String blocker(){return resumeBlocker;}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;long now=System.currentTimeMillis();health.tick(now);hivemind.getActions().manual(hivemind.isPlayerClient());
        if(event.getType()==TickEvent.Type.IN&&online()&&!hivemind.isPlayerClient()){
            ensure();if(Math.hypot(ctx.player().getX()-lastX,ctx.player().getZ()-lastZ)>3){lastX=ctx.player().getX();lastZ=ctx.player().getZ();lastProgress=now;health.progress(now);}
            if(now>=nextSample){nextSample=now+1000;observe(now);}
        }
        if(now>=nextHeartbeat&&!writingHeartbeat){nextHeartbeat=now+2000;heartbeat(now);}
    }
    private void observe(long now){
        List<ShulkerManifest.Box> boxes=new ArrayList<>();for(int slot=0;slot<36;slot++){var stack=ctx.player().getInventory().getItem(slot);if(!ExpeditionLoadout.portable(stack))continue;var component=stack.get(net.minecraft.core.component.DataComponents.CONTAINER);Map<String,Integer> counts=new LinkedHashMap<>();if(component!=null){var contents=net.minecraft.core.NonNullList.<net.minecraft.world.item.ItemStack>withSize(27,net.minecraft.world.item.ItemStack.EMPTY);component.copyInto(contents);for(var item:contents)if(!item.isEmpty())counts.merge(BotItems.id(item),item.getCount(),Integer::sum);}boxes.add(new ShulkerManifest.Box(slot,BotItems.id(stack),boxSignature(stack),component!=null,counts));}manifest.observe(boxes,now);
        offlinePolicy=HiveMind.settings().botControllerOfflinePolicy.value;
        long directive=HiveMind.settings().botFleetControlRevision.value;
        if(directive>task.fleetRevision()){
            task.fleetRevision(directive);task.recall(HiveMind.settings().botFleetRecall.value);
            task.hold(HiveMind.settings().botFleetHold.value||task.recall());hivemind.getAutonomy().pause(task.held());
            if(!task.recall())hivemind.getOperations().cancelRecall();
        }
        if(task.recall()&&!recallArrived()){
            if(hivemind.getStalkProcess().isActive())hivemind.getStalkProcess().onLostControl();
            hivemind.getOperations().requestRecall();
        }
        boolean connected=hivemind.getNetwork().controllerConnected();if(connected){controllerSeen=true;controllerLost=0;offlineHold=false;returnedForOutage=false;}else if(controllerSeen){if(controllerLost==0)controllerLost=now;offlineHold=(offlinePolicy.equals("hold")||offlinePolicy.equals("return")&&returnedForOutage)&&now-controllerLost>15000;if(offlinePolicy.equals("return")&&!returnedForOutage&&now-controllerLost>15000&&!hivemind.getOperations().returnForOutage()){offlineHold=true;}}
        if(offlineHold&&hivemind.getFlight().flying()&&hivemind.getElytraProcess() instanceof ElytraProcess flight)flight.requestLanding();
        List<TaskCheckpoint.Interruption> stack=new ArrayList<>();
        if(hivemind.getConflictProcess().isActive())stack.add(new TaskCheckpoint.Interruption("combat","Threat response","Threat cleared and equipment checked",now));
        if(hivemind.getSurvivalProcess().isActive())stack.add(new TaskCheckpoint.Interruption("survival",hivemind.getSurvivalProcess().status(),"Health, food and equipment ready",now));
        if(hivemind.getShulkerProcess().isActive()||hivemind.getShulkerProcess().hasPendingRecovery())stack.add(new TaskCheckpoint.Interruption("portable supplies","Restocking or recovering a placed box","Box recovered and actual supplies checked",now));
        if(hivemind.getDepositProcess().isActive())stack.add(new TaskCheckpoint.Interruption("storage","Storage trip","Confirmed transfer and return to task",now));
        if(hivemind.getPortalTravelProcess().isActive())stack.add(new TaskCheckpoint.Interruption("portal","Dimension transfer","Expected dimension and exit observed",now));
        if(now<deathHold)stack.add(new TaskCheckpoint.Interruption("death recovery","Repeated deaths in one area","Regroup cooldown or explicit resume",now));
        if(offlineHold)stack.add(new TaskCheckpoint.Interruption("controller","Controller connection lost","Controller reconnected",now));
        if(updateHold)stack.add(new TaskCheckpoint.Interruption("update","Preparing a supervised update restart","Inventory work finished and a safe checkpoint reached",now));
        task.observed(stack);
        captureResume();restore(now);
        String decision="intent="+task.intent()+"; owners="+hivemind.getActions().snapshot()+"; interruptions="+stack.stream().map(TaskCheckpoint.Interruption::owner).toList()+"; blocker="+hivemind.getOperations().blocker();if(!decision.equals(lastTrace)){lastTrace=decision;trace.addLast(java.time.Instant.ofEpochMilli(now)+" "+decision);while(trace.size()>128)trace.removeFirst();}
        // A persisted hold also reaches the old supervisors, which must not start new work underneath it.
        if(task.held()&&!hivemind.getAutonomy().paused())hivemind.getAutonomy().pause(true);
    }
    private void captureResume(){
        if(task.intent().isBlank())return;String label=task.intent().trim().replaceFirst("^#","").split("\\s+",2)[0];
        if(Set.of("deliver","escort","recover","gather","assist").contains(label)&&hivemind.getCompanionProcess().isActive()){String resume=hivemind.getCompanionProcess().resumeCommand();if(resume!=null)task.resumeWith(resume);}
        if(Set.of("goto","path").contains(label)){
            var goal=hivemind.getCustomGoalProcess().getGoal();if(goal instanceof hivemind.api.pathing.goals.GoalBlock g)task.resumeWith("goto "+g.x+" "+g.y+" "+g.z);else if(goal instanceof hivemind.api.pathing.goals.GoalXZ g)task.resumeWith("goto "+g.getX()+" "+g.getZ());
        }
    }
    private void restore(long now){
        if(task.intent().isBlank()||task.held()||holding()||now<nextRestore||!ctx.player().isAlive()||hivemind.getAutonomy().paused()||hivemind.getCompanionProcess().awaitingDeathInventory()||hivemind.getJobManager().hasQueuedJobs()||hivemind.getAutonomy().order()!=null)return;
        if(BotWork.foreground(hivemind)||hivemind.getFlight().flying()){resumeBlocker="";return;}
        if(inventoryNeedsReconcile||ctx.minecraft().gui.screen()!=null||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty()||hivemind.getShulkerProcess().hasPendingRecovery()){resumeBlocker="Waiting for inventory reconciliation or placed-box recovery";nextRestore=now+2000;return;}
        String command=task.resume(),label=command.trim().replaceFirst("^#","").split("\\s+",2)[0];
        if(!Set.of("mine","goto","follow","explore","gather","repair","recover","assist","deliver","escort","scout").contains(label)){resumeBlocker="Saved intent retained; its task prerequisites must be restored before resuming";nextRestore=now+10000;return;}
        try{
            if((label.equals("deliver")||command.startsWith("assist bring "))&&delivery.value()!=null){
                if(delivery.uncertain())throw new IllegalStateException("Handoff pickup unconfirmed; inspect it and use deliver resolve received/returned/lost");
                if(delivery.value().delivered()>=delivery.value().quantity()){task.completed("Recipient pickup confirmed before restart");return;}
                restoring=true;try{hivemind.getCompanionProcess().restoreDelivery();}finally{restoring=false;}resumeBlocker="";nextRestore=now+10000;return;
            }
            if(!label.equals("mine")&&!task.dimension().isBlank()&&!task.dimension().equals(MiningRouteAccess.dimension(ctx))){if(!hivemind.getPortalTravelProcess().isActive())hivemind.getPortalTravelProcess().start(task.dimension());nextRestore=now+5000;return;}
            if(label.equals("scout")&&hivemind.getScoutProcess().queue().first()!=null){hivemind.getScoutProcess().queue().pause(false);resumeBlocker="";nextRestore=now+5000;return;}
            var expanded=CommandManager.expand(command);var target=hivemind.getCommandManager().getCommand(expanded.first());if(target==null)throw new IllegalStateException("Saved command is unavailable");
            restoring=true;try{target.execute(expanded.first(),new ArgConsumer(hivemind.getCommandManager(),expanded.second()));}finally{restoring=false;}
            resumeBlocker="";nextRestore=now+10000;failures.success("resume:"+task.generation());
        }catch(Exception error){restoring=false;var decision=failed("resume:"+task.generation(),Objects.toString(error.getMessage(),"Task prerequisites unavailable"),0);nextRestore=decision.retryAt();resumeBlocker=decision.reason();}
    }
    private void heartbeat(long now){
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("pid",""+ProcessHandle.current().pid());p.setProperty("tick",""+now);p.setProperty("role",hivemind.isPlayerClient()?"player":"bot");p.setProperty("connected",""+online());p.setProperty("held",""+(task!=null&&task.held()));p.setProperty("generation",""+(task==null?0:task.generation()));p.setProperty("progress",""+lastProgress);p.setProperty("intent",task==null?"":task.intent());
        writingHeartbeat=true;Path file=hivemind.getDirectory().resolve("runtime-health.properties");disk.execute(()->{try{AtomicProperties.write(file,p);}finally{writingHeartbeat=false;}});
    }
    @Override public void onReceivePacket(PacketEvent event){long now=System.currentTimeMillis();health.server(now);String type=event.getPacket().getClass().getSimpleName();if(type.contains("PlayerPosition"))health.correction(now);}
    @Override public void onWorldEvent(WorldEvent event){if(event.getState()==EventState.PRE){hivemind.getActions().reset();if(inventory!=null&&inventory.pending()!=null)inventoryNeedsReconcile=true;}}
    @Override public void onPlayerDeath(){
        if(hivemind.isPlayerClient()||!online())return;ensure();long now=System.currentTimeMillis();Endpoint here=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());
        if(deathArea==null||!deathArea.dimension().equals(here.dimension())||Math.hypot((double)deathArea.x()-here.x(),(double)deathArea.z()-here.z())>96)deaths.clear();deathArea=here;deaths.addLast(now);while(!deaths.isEmpty()&&now-deaths.peekFirst()>600000)deaths.removeFirst();
        if(deathCircuit.died(here,now)){deathHold=deathCircuit.until();hivemind.getOperations().journal().note("Recovery","Repeated deaths; regrouping for five minutes before retrying the retained task");}
    }
    public Map<String,String> details(){
        Map<String,String> m=new LinkedHashMap<>();m.put("owners",hivemind.getActions().snapshot().toString());m.put("actionBlocker",hivemind.getActions().blocker());m.put("failure",lastFailure);m.put("recovery",nextRecovery);
        if(task!=null){m.put("intent",task.intent());m.put("generation",""+task.generation());m.put("interruptions",task.stack().stream().map(i->i.owner()+": "+i.resumeWhen()).reduce((a,b)->a+"; "+b).orElse("None"));m.put("held",""+task.held());}
        if(manifest!=null)m.put("boxes",manifest.summary());if(delivery!=null&&delivery.uncertain())m.put("handoff","Unconfirmed pickup: "+delivery.value().pending()+" "+delivery.value().item());if(inventory!=null)m.put("inventory",inventory.outcome());m.put("runtime",health.snapshot(System.currentTimeMillis()).toString());return m;
    }
    @Override public void onLostControl(){/* Interruptions never erase the original intent or uncertain inventory work. */}
    @Override public String displayName0(){return task!=null&&task.held()?"Held by controller":"Waiting for recovery conditions";}
}

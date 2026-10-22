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
import hivemind.api.Settings;
import hivemind.command.argument.ArgConsumer;
import hivemind.command.manager.CommandManager;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.bot.SessionRuntime;
import hivemind.utils.update.UpdateRuntime;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.network.*;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.network.NetworkProtocol.Identity;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import static hivemind.utils.network.NetworkAtlas.quote;

/** Client-thread control adapter. Socket/HTTP workers can enqueue requests but never mutate game state. */
final class NetworkControls {
    private record Pending(String description, List<UUID> expected, long time) {}
    private final NetworkBehavior parent;
    private final HiveMind bot;
    private final Path settingsFile;
    private final ControlRules rules;
    private final ControlRules.Coordinator coordinator;
    private final ArrayBlockingQueue<WebControlAction> actions=new ArrayBlockingQueue<>(64);
    private final Map<String,Object> before=new HashMap<>();
    private final Map<String,String> managed=new LinkedHashMap<>();
    private final Map<UUID,Pending> pending=new LinkedHashMap<>();
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    private final Map<String,Receipt> applied=new LinkedHashMap<>();
    private final Map<UUID,Long> lastSync=new HashMap<>();
    private Defaults group;
    private State lastSnapshot;
    private long lastOperations;
    private long appliedVersion,lastState,lastReplayEpoch=-1;
    private final Deque<Receipt> replayReceipts=new ArrayDeque<>();
    private volatile boolean closed;
    NetworkControls(NetworkBehavior parent,HiveMind bot,Path root){
        this.parent=parent;this.bot=bot;settingsFile=root.resolve("bot-web-settings.properties");
        rules=new ControlRules(root);coordinator=new ControlRules.Coordinator(rules,parent.localIdentity());
        Properties saved=AtomicProperties.read(settingsFile);
        if(saved.size()>128)throw new IllegalStateException("Too many saved web settings");
        Map<String,String> values=new LinkedHashMap<>();
        for(String key:saved.stringPropertyNames())values.put(key,saved.getProperty(key));
        setValues(NetworkSettings.normalize(values));
    }
    boolean enqueue(WebControlAction action){return !closed&&actions.offer(action);}
    private Identity who(){return parent.localIdentity();}
    private boolean authority(){return parent.config().options().mode()!=NetworkConfig.Mode.JOIN;}
    private void publish(Packet packet){BotNetwork wire=parent.transport();if(wire!=null)wire.publish(packet);}
    void connectionChanged(){appliedVersion=lastState=0;lastReplayEpoch=-1;replayReceipts.clear();lastSync.clear();group=null;}
    void accept(Packet packet,long now){
        if(!packet.valid(now))return;
        if(packet instanceof Request request){
            if(authority())dispatch(request,now);
        }else if(packet instanceof Receipt receipt)remember(receipt);
        else if(packet instanceof Defaults defaults){group=defaults;}
        else if(packet instanceof Execute execute){
            if(execute.target()!=null&&!execute.target().equals(who().id()))return;
            if(bot.isPlayerClient()&&execute.target()==null)return;
            applyOnce(execute.reporter(),execute.id(),execute.requester(),()->run(execute.command()),now);
        }else if(packet instanceof Policy policy){
            if(bot.isPlayerClient())return;
            if(!policy.target().equals(who().id())||bot.getPlayerContext().player()==null||bot.getPlayerContext().world()==null)return;
            applyOnce(policy.reporter(),policy.id(),policy.requester(),()->{
                Map<String,String> values=NetworkSettings.normalize(policy.settings());
                var trust=bot.getBotControl().config();Set<UUID> next=new HashSet<>(trust.players());
                policy.trust().forEach((id,add)->{if(add)next.add(id);else next.remove(id);});
                if(next.size()>128)throw new IllegalArgumentException("Too many trusted controllers");
                Properties saved=new Properties();managed.forEach(saved::setProperty);values.forEach(saved::setProperty);
                // Commit the managed settings separately from global HiveMind defaults; restore them when leaving this server.
                boolean changed=!values.entrySet().stream().allMatch(entry->Objects.equals(managed.get(entry.getKey()),entry.getValue()));
                Properties previous=changed?AtomicProperties.read(settingsFile):null;
                if(changed)AtomicProperties.write(settingsFile,saved);
                try{trust.apply(policy.trust());}
                catch(RuntimeException error){if(changed)try{AtomicProperties.write(settingsFile,previous);}catch(RuntimeException rollback){error.addSuppressed(rollback);}throw error;}
                setValues(values);appliedVersion=policy.version();
                return "Settings and trust saved and applied";
            },now);
        }
    }
    private interface Operation{String run()throws Exception;}
    private void applyOnce(UUID source,UUID id,UUID requester,Operation operation,long now){
        String key=source+":"+id;Receipt previous=applied.get(key);
        if(previous!=null){remember(previous);publish(previous);return;}
        applied.values().removeIf(receipt->!receipt.valid(now));
        if(applied.size()>=1024){Receipt busy=receipt(id,requester,false,"Too many actions; try again shortly",now);remember(busy);publish(busy);return;}
        Receipt response;
        try{response=receipt(id,requester,true,operation.run(),now);}
        catch(Exception error){response=receipt(id,requester,false,"Could not apply action: "+Objects.toString(error.getMessage(),error.getClass().getSimpleName()),now);}
        applied.put(key,response);remember(response);publish(response);lastState=0;
    }
    private Receipt receipt(UUID id,UUID requester,boolean success,String message,long now){
        return new Receipt(who().id(),who().name(),id,requester,success,shorten(message,500),now);
    }
    private void remember(Receipt receipt){
        receipts.put(receipt.id()+":"+receipt.reporter(),receipt);
        while(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());
    }
    private void dispatch(Request request,long now){
        List<UUID> online=states(now).stream().filter(state->!ClientRole.player(state.session())).map(State::reporter).toList();
        for(Packet packet:coordinator.handle(request,online,now)){
            accept(packet,now);publish(packet);
        }
    }
    void tick(long now){
        WebControlAction action;int count=0;
        while(count++<8&&(action=actions.poll())!=null){
            String description=action.action()==Action.COMMAND?action.value():action.action()==Action.SETTING?"Set "+action.key()+" = "+action.value():(action.value().equals("true")?"Trust ":"Untrust ")+action.key();
            WebControlAction current=action;
            List<UUID> expected=states(now).stream().filter(state->!ClientRole.player(state.session())).map(State::reporter).filter(id->actionTarget(current,id)).toList();
            pending.put(action.id(),new Pending(description,expected,now));
            while(pending.size()>32)pending.remove(pending.keySet().iterator().next());
            try{
                if(now-action.time()>60000)throw new IllegalArgumentException("Request expired before the client could process it");
                String key=action.key();
                if(action.action()==Action.TRUST)key=resolve(key).toString();
                Request request=new Request(who().id(),who().name(),action.id(),action.target(),action.action(),key,action.value(),now);
                if(authority())dispatch(request,now);
                else if(parent.transport()==null||!parent.transport().connected()||!parent.transport().publish(request))throw new IllegalStateException("Host disconnected; action was not sent");
            }catch(RuntimeException error){remember(receipt(action.id(),who().id(),false,Objects.toString(error.getMessage(),"Action rejected"),now));}
        }
        receipts.values().removeIf(receipt->!receipt.valid(now));
        if(now-lastState<500)return;
        lastState=now;State local=state(now);publish(local);if(now-lastOperations>=1000){lastOperations=now;publish(activity(now));}
        if(authority()){
            group=new Defaults(who().id(),who().name(),rules.version(),rules.settings(null),rules.trust(null),now);
            publish(group);
            if(rules.version()>0)for(State state:states(now)){
                if(ClientRole.player(state.session())||state.policyVersion()==rules.version()||now-lastSync.getOrDefault(state.reporter(),0L)<10000)continue;
                lastSync.put(state.reporter(),now);
                Policy policy=rules.policy(who(),UUID.randomUUID(),who().id(),state.reporter(),now);
                accept(policy,now);publish(policy);
            }
        }
        // A lost connection never replays commands. Only their already-produced receipts are repeated briefly.
        BotNetwork wire=parent.transport();
        if(wire!=null&&!wire.isHost()&&wire.connected()){
            if(lastReplayEpoch!=wire.connectionEpoch()){
                lastReplayEpoch=wire.connectionEpoch();replayReceipts.clear();
                applied.values().stream().filter(receipt->receipt.valid(now)).skip(Math.max(0,applied.size()-16)).forEach(replayReceipts::add);
            }
            // Pace reconnection receipts so a large team cannot overflow every peer's priority queue.
            for(int i=0;i<2&&!replayReceipts.isEmpty();i++){Receipt receipt=replayReceipts.poll();if(receipt.valid(now))publish(receipt);}
        }
    }
    private static boolean actionTarget(WebControlAction action,UUID id){return action.target()==null||action.target().equals(id);}
    private UUID resolve(String name){
        try{return UUID.fromString(name);}catch(IllegalArgumentException ignored){}
        return bot.getPlayerContext().minecraft().getConnection().getOnlinePlayers().stream()
                .filter(info->info.getProfile().name().equalsIgnoreCase(name)).map(info->info.getProfile().id()).findFirst()
                .orElseThrow(()->new IllegalArgumentException("That player is not online; supply an exact UUID"));
    }
    private String run(String text)throws Exception{
        String command=ControlRules.command(text);var expanded=CommandManager.expand(command);
        String label=expanded.first().toLowerCase(Locale.ROOT);var args=new ArgConsumer(bot.getCommandManager(),expanded.second());
        if(bot.isPlayerClient()&&!ClientRole.remoteAllowed(label))throw new IllegalStateException("Player client is under manual control");
        if(label.equals("session")){
            bot.getCommandManager().getCommand(label).execute(label,args);
            return SessionRuntime.get(bot.getPlayerContext().minecraft().gameDirectory.toPath()).status();
        }
        if(label.equals("update")){
            var target=bot.getCommandManager().getCommand(label);target.execute(label,args);return UpdateRuntime.service().summary();
        }
        if(bot.getPlayerContext().player()==null||bot.getPlayerContext().world()==null)throw new IllegalStateException("Minecraft is offline; reconnect this client first");
        if(label.equals("status")){args.requireMax(0);return status();}
        if(label.equals("mine")&&expanded.second().size()>=2&&expanded.second().get(0).getValue().matches("[1-9][0-9]*")){
            int quantity=args.getAs(Integer.class);List<String> blocks=new ArrayList<>();while(args.hasAny())blocks.add(args.getString());
            long id=bot.getJobManager().submit(quantity,blocks);bot.getAutonomy().afterCommand(label,"start");return "Queued mining job #"+id;
        }
        var target=bot.getCommandManager().getCommand(label);if(target==null)throw new IllegalArgumentException("Unknown bot command");
        String first=expanded.second().isEmpty()?"":expanded.second().get(0).getValue().toLowerCase(Locale.ROOT);bot.getAutonomy().beforeCommand(label,first);
        target.execute(label,args);bot.getAutonomy().afterCommand(label,first);return "Accepted #"+command+"; "+status();
    }
    OperationsPackets.Snapshot activity(long now){
        Map<String,String> details=new LinkedHashMap<>();var ctx=bot.getPlayerContext();boolean online=ctx.player()!=null&&ctx.world()!=null;
        var controlled=bot.getPathingControlManager().mostRecentInControl().orElse(null);
        details.put("task",bot.isPlayerClient()?"Manual player sharing":controlled==null?"Idle":controlled.displayName());
        details.put("blocker",bot.isPlayerClient()?"":bot.getOperations().blocker());details.put("next",bot.isPlayerClient()?"Share observations without controlling this character":bot.getOperations().nextAction());
        if(online&&!bot.isPlayerClient()&&bot.getSurvivalProcess().isActive())details.put("blocker",bot.getSurvivalProcess().status());
        details.put("flight",bot.isPlayerClient()?"Manual":bot.getFlight().status());details.put("recoveries",""+bot.getOperations().recoveries());
        details.put("available",""+(online&&!bot.isPlayerClient()&&!hivemind.utils.bot.BotWork.foreground(bot)&&!bot.getJobManager().hasQueuedJobs()&&!bot.getAutonomy().paused()&&bot.getAutonomy().order()==null));
        if(parent.transport()!=null)details.putAll(parent.transport().health());
        var queue=online&&!bot.isPlayerClient()?bot.getScoutProcess().queue():null;
        if(online&&!bot.isPlayerClient()&&bot.getPathingBehavior().getGoal()!=null)details.put("goal",bot.getPathingBehavior().getGoal().toString());
        if(queue!=null&&queue.first()!=null&&!bot.getOperations().isActive()&&details.get("blocker").isEmpty())details.put("next",queue.paused()?"Waiting for map queue resume":queue.tasks().size()>1?"Finish this task, then "+queue.tasks().get(1).kind()+" at "+queue.tasks().get(1).point().x()+" "+queue.tasks().get(1).point().z():"Finish the current map task");
        details.replaceAll((key,value)->shorten(value,256));return new OperationsPackets.Snapshot(who().id(),who().name(),details,queue==null?List.of():queue.tasks(),queue!=null&&queue.paused(),now);
    }
    private String status(){
        if(bot.isPlayerClient())return "Player client · sharing terrain and sightings";
        return shorten(bot.getScoutProcess().status()+" · "+bot.getAutonomy().status()+" · "+bot.getStalkProcess().status()+" · "+bot.getFlight().status()+" · "+bot.getJobManager().status()+" · "+bot.getCompanionProcess().status()+" · "+bot.getNetwork().team().status()+" · "+bot.getRoamProcess().status(),500);
    }
    private static String shorten(String text,int length){return text.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(length,text.length()));}
    private Settings.Setting<?> setting(String name){
        var setting=HiveMind.settings().byLowerName.get(name.toLowerCase(Locale.ROOT));
        if(setting==null)throw new IllegalStateException("Unknown configured bot setting: "+name);
        return setting;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void setValues(Map<String,String> values){
        Map<String,Object> parsed=new LinkedHashMap<>();
        values.forEach((name,value)->{
            var setting=setting(name);Object result;
            if(setting.value instanceof Boolean)result=Boolean.valueOf(value);
            else if(setting.value instanceof Long)result=Long.valueOf(value);
            else if(setting.value instanceof Integer)result=Integer.valueOf(value);
            else result=value;
            parsed.put(name,result);
        });
        parsed.forEach((name,value)->{var setting=setting(name);before.putIfAbsent(name,setting.value);((Settings.Setting)setting).value=value;managed.put(name,values.get(name));});
    }
    State state(long now){
        var ctx=bot.getPlayerContext();var session=SessionRuntime.get(ctx.minecraft().gameDirectory.toPath());
        Map<String,String> sessionState=new LinkedHashMap<>(session.snapshot());sessionState.put("role",bot.getClientRole().mode());
        sessionState.put("connected",Boolean.toString(ctx.player()!=null&&ctx.world()!=null&&ctx.minecraft().getConnection()!=null));if(bot.isPlayerClient())sessionState.put("automatic","false");
        Map<String,String> update=UpdateRuntime.ready()?UpdateRuntime.service().snapshot():Map.of();
        Map<String,String> settings=new LinkedHashMap<>();for(var spec:NetworkSettings.all())settings.put(spec.name(),setting(spec.name()).value.toString());
        if(ctx.player()==null||ctx.world()==null||ctx.minecraft().getConnection()==null){
            State old=lastSnapshot;
            return new State(who().id(),who().name(),session.status(),old==null?0:old.health(),old==null?0:old.food(),
                old==null?new hivemind.utils.mining.WorldRoutes.Endpoint("minecraft:overworld",0,64,0):old.point(),old==null?Map.of():old.trusted(),settings,Map.of(),appliedVersion,now,update,sessionState);
        }
        Map<UUID,String> players=new LinkedHashMap<>(),trusted=new LinkedHashMap<>();
        ctx.minecraft().getConnection().getOnlinePlayers().stream().limit(256).forEach(info->players.put(info.getProfile().id(),info.getProfile().name()));
        for(UUID id:bot.getBotControl().config().players())trusted.put(id,players.getOrDefault(id,id.toString()));
        lastSnapshot=new State(who().id(),who().name(),status(),Math.max(0,Math.min(1024,ctx.player().getHealth())),Math.max(0,Math.min(20,ctx.player().getFoodData().getFoodLevel())),
                MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),trusted,settings,players,appliedVersion,now,update,sessionState);return lastSnapshot;
    }
    List<State> states(long now){
        Map<UUID,State> nodes=new LinkedHashMap<>();if(parent.transport()!=null)for(State state:parent.transport().states())nodes.put(state.reporter(),state);
        nodes.put(who().id(),state(now));return nodes.values().stream().sorted(Comparator.comparing(State::reporterName)).toList();
    }
    String json(long now){
        List<ControlView.History> history=new ArrayList<>();
        for(var entry:pending.entrySet())history.add(new ControlView.History(entry.getKey(),entry.getValue().description(),entry.getValue().expected(),entry.getValue().time(),receipts.values().stream().filter(receipt->receipt.id().equals(entry.getKey())).toList()));
        Collections.reverse(history);List<State> nodes=states(now);
        Map<UUID,OperationsPackets.Snapshot> ops=new LinkedHashMap<>();if(parent.transport()!=null)parent.transport().operations().forEach(p->ops.put(p.reporter(),p));ops.put(who().id(),activity(now));
        return ControlView.withOperations(ControlView.json(who(),authority(),authority()||parent.transport()!=null&&parent.transport().connected(),parent.config().options().key(),nodes,
            nodes.stream().map(State::reporter).collect(java.util.stream.Collectors.toSet()),group==null?Map.of():group.settings(),group==null?Map.of():group.trust(),history,Map.of()),ops.values());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) void close(){
        closed=true;actions.clear();
        before.forEach((name,value)->{var setting=setting(name);if(Objects.equals(setting.value.toString(),managed.get(name)))((Settings.Setting)setting).value=value;});
    }
}

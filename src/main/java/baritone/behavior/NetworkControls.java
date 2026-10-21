/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
package baritone.behavior;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.command.argument.ArgConsumer;
import baritone.command.manager.CommandManager;
import baritone.utils.bot.AtomicProperties;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.network.*;
import baritone.utils.network.ControlPackets.*;
import baritone.utils.network.NetworkProtocol.Identity;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import static baritone.utils.network.NetworkAtlas.quote;

/** Client-thread control adapter. Socket/HTTP workers can enqueue requests but never mutate game state. */
final class NetworkControls {
    private record Pending(String description, List<UUID> expected, long time) {}
    private final NetworkBehavior parent;
    private final Baritone bot;
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
    private long appliedVersion,lastState;
    private volatile boolean closed;
    NetworkControls(NetworkBehavior parent,Baritone bot,Path root){
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
    void connectionChanged(){appliedVersion=lastState=0;lastSync.clear();group=null;}
    void accept(Packet packet,long now){
        if(!packet.valid(now))return;
        if(packet instanceof Request request){
            if(authority())dispatch(request,now);
        }else if(packet instanceof Receipt receipt)remember(receipt);
        else if(packet instanceof Defaults defaults){group=defaults;}
        else if(packet instanceof Execute execute){
            if(execute.target()!=null&&!execute.target().equals(who().id()))return;
            applyOnce(execute.reporter(),execute.id(),execute.requester(),()->run(execute.command()),now);
        }else if(packet instanceof Policy policy){
            if(!policy.target().equals(who().id()))return;
            applyOnce(policy.reporter(),policy.id(),policy.requester(),()->{
                Map<String,String> values=NetworkSettings.normalize(policy.settings());
                var trust=bot.getBotControl().config();Set<UUID> next=new HashSet<>(trust.players());
                policy.trust().forEach((id,add)->{if(add)next.add(id);else next.remove(id);});
                if(next.size()>128)throw new IllegalArgumentException("Too many trusted controllers");
                Properties saved=new Properties();managed.forEach(saved::setProperty);values.forEach(saved::setProperty);
                // Commit the managed settings separately from global Baritone defaults; restore them when leaving this server.
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
        List<UUID> online=states(now).stream().map(State::reporter).toList();
        for(Packet packet:coordinator.handle(request,online,now)){
            accept(packet,now);publish(packet);
        }
    }
    void tick(long now){
        WebControlAction action;int count=0;
        while(count++<8&&(action=actions.poll())!=null){
            String description=action.action()==Action.COMMAND?action.value():action.action()==Action.SETTING?"Set "+action.key()+" = "+action.value():(action.value().equals("true")?"Trust ":"Untrust ")+action.key();
            WebControlAction current=action;
            List<UUID> expected=states(now).stream().map(State::reporter).filter(id->actionTarget(current,id)).toList();
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
        if(now-lastState<2000)return;
        lastState=now;State local=state(now);publish(local);
        if(authority()){
            group=new Defaults(who().id(),who().name(),rules.version(),rules.settings(null),rules.trust(null),now);
            publish(group);
            if(rules.version()>0)for(State state:states(now)){
                if(state.policyVersion()==rules.version()||now-lastSync.getOrDefault(state.reporter(),0L)<10000)continue;
                lastSync.put(state.reporter(),now);
                Policy policy=rules.policy(who(),UUID.randomUUID(),who().id(),state.reporter(),now);
                accept(policy,now);publish(policy);
            }
        }
        // A lost connection never replays commands. Only their already-produced receipts are repeated briefly.
        applied.values().stream().filter(receipt->receipt.valid(now)).skip(Math.max(0,applied.size()-16)).forEach(this::publish);
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
        if(label.equals("status")){args.requireMax(0);return status();}
        if(label.equals("mine")&&expanded.second().size()>=2&&expanded.second().get(0).getValue().matches("[1-9][0-9]*")){
            int quantity=args.getAs(Integer.class);List<String> blocks=new ArrayList<>();while(args.hasAny())blocks.add(args.getString());
            long id=bot.getJobManager().submit(quantity,blocks);return "Queued mining job #"+id;
        }
        var target=bot.getCommandManager().getCommand(label);if(target==null)throw new IllegalArgumentException("Unknown bot command");
        target.execute(label,args);return "Accepted #"+command+"; "+status();
    }
    private String status(){
        return shorten(bot.getJobManager().status()+" · "+bot.getCompanionProcess().status()+" · "+bot.getStalkProcess().status()+" · "+bot.getNetwork().team().status()+" · "+bot.getRoamProcess().status(),500);
    }
    private static String shorten(String text,int length){return text.replaceAll("[\\p{Cntrl}]"," ").substring(0,Math.min(length,text.length()));}
    private Settings.Setting<?> setting(String name){
        var setting=Baritone.settings().byLowerName.get(name.toLowerCase(Locale.ROOT));
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
        var ctx=bot.getPlayerContext();Map<UUID,String> players=new LinkedHashMap<>(),trusted=new LinkedHashMap<>();
        ctx.minecraft().getConnection().getOnlinePlayers().stream().limit(256).forEach(info->players.put(info.getProfile().id(),info.getProfile().name()));
        for(UUID id:bot.getBotControl().config().players())trusted.put(id,players.getOrDefault(id,id.toString()));
        Map<String,String> settings=new LinkedHashMap<>();for(var spec:NetworkSettings.all())settings.put(spec.name(),setting(spec.name()).value.toString());
        return new State(who().id(),who().name(),status(),Math.max(0,Math.min(1024,ctx.player().getHealth())),Math.max(0,Math.min(20,ctx.player().getFoodData().getFoodLevel())),
                MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),trusted,settings,players,appliedVersion,now);
    }
    List<State> states(long now){
        Map<UUID,State> nodes=new LinkedHashMap<>();if(parent.transport()!=null)for(State state:parent.transport().states())nodes.put(state.reporter(),state);
        nodes.put(who().id(),state(now));return nodes.values().stream().sorted(Comparator.comparing(State::reporterName)).toList();
    }
    String json(long now){
        StringBuilder out=new StringBuilder("{\"ready\":true,\"local\":").append(quote(who().id().toString())).append(",\"host\":").append(authority())
                .append(",\"connected\":").append(authority()||parent.transport()!=null&&parent.transport().connected()).append(",\"networkKey\":").append(quote(parent.config().options().key())).append(",\"bots\":[");
        StringJoiner list=new StringJoiner(",");
        for(State state:states(now)){
            list.add("{\"id\":"+quote(state.reporter().toString())+",\"name\":"+quote(state.reporterName())+",\"status\":"+quote(state.status())
                    +",\"health\":"+state.health()+",\"food\":"+state.food()+",\"dimension\":"+quote(state.point().dimension())+",\"x\":"+state.point().x()+",\"y\":"+state.point().y()+",\"z\":"+state.point().z()
                    +",\"trusted\":"+people(state.trusted())+",\"settings\":"+strings(state.settings())+",\"players\":"+people(state.players())+",\"t\":"+state.observedAt()+"}");
        }
        out.append(list).append("],\"schema\":[");list=new StringJoiner(",");
        for(var spec:NetworkSettings.all())list.add("{\"name\":"+quote(spec.name())+",\"group\":"+quote(spec.group())+",\"type\":"+quote(spec.type())+",\"min\":"+spec.min()+",\"max\":"+spec.max()+",\"choices\":["+spec.choices().stream().map(NetworkAtlas::quote).reduce((a,b)->a+","+b).orElse("")+"]}");
        out.append(list).append("],\"defaults\":").append(group==null?"{}":strings(group.settings())).append(",\"trustDefaults\":{");
        StringJoiner trustDefaults=new StringJoiner(",");if(group!=null)group.trust().forEach((id,add)->trustDefaults.add(quote(id.toString())+":"+add));
        out.append(trustDefaults).append("},\"actions\":[");list=new StringJoiner(",");
        List<Map.Entry<UUID,Pending>> history=new ArrayList<>(pending.entrySet());Collections.reverse(history);
        for(var entry:history){
            StringJoiner replies=new StringJoiner(",");
            for(Receipt receipt:receipts.values())if(receipt.id().equals(entry.getKey()))replies.add("{\"bot\":"+quote(receipt.reporter().toString())+",\"name\":"+quote(receipt.reporterName())+",\"ok\":"+receipt.success()+",\"message\":"+quote(receipt.message())+"}");
            Pending request=entry.getValue();
            list.add("{\"id\":"+quote(entry.getKey().toString())+",\"description\":"+quote(request.description())+",\"t\":"+request.time()+",\"expected\":["+request.expected().stream().map(id->quote(id.toString())).reduce((a,b)->a+","+b).orElse("")+"],\"results\":["+replies+"]}");
        }
        return out.append(list).append("]}").toString();
    }
    private static String strings(Map<String,String> map){StringJoiner join=new StringJoiner(",","{","}");map.forEach((key,value)->join.add(quote(key)+":"+quote(value)));return join.toString();}
    private static String people(Map<UUID,String> map){Map<String,String> values=new LinkedHashMap<>();map.forEach((id,name)->values.put(id.toString(),name));return strings(values);}
    @SuppressWarnings({"rawtypes","unchecked"}) void close(){
        closed=true;actions.clear();
        before.forEach((name,value)->{var setting=setting(name);if(Objects.equals(setting.value.toString(),managed.get(name)))((Settings.Setting)setting).value=value;});
    }
}

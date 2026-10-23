/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.network.*;
import hivemind.utils.network.NetworkProtocol.*;
import hivemind.utils.network.ControlPackets.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
/** A Minecraft-independent, durable control authority. Only the loop mutates coordination state. */
public final class Controller implements AutoCloseable {
    private final Path root;
    private final String key,scope,advertise;
    private final Identity identity;
    private final BotNetwork wire;
    private final LocalNetworkViewer web;
    private final NetworkAtlas atlas;
    private final TerrainStore terrain;
    private final ControlRules rules;
    private final ControlRules.Coordinator coordinator;
    private final Teamwork teamwork;
    private final SessionDecisions sessions=new SessionDecisions();
    private final ArrayBlockingQueue<WebControlAction> actions=new ArrayBlockingQueue<>(64);
    private final Map<UUID,Contact> contacts=new LinkedHashMap<>();
    private final Map<UUID,State> known=new LinkedHashMap<>();
    private final Map<UUID,OperationsPackets.Snapshot> lastOperations=new LinkedHashMap<>();
    private final Map<UUID,ControlView.History> pending=new LinkedHashMap<>();
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    private final Map<UUID,Long> synced=new HashMap<>();
    private final ScheduledExecutorService loop;
    private long lastPlan,lastPage,lastSave,lastDefaults;
    private volatile String error="";
    private volatile boolean closed;
    public Controller(Path root,String bind,int port,String webBind,int webPort,URI publicUrl,String advertise,String scope)throws Exception{
        this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);this.scope=scope;this.advertise=advertise;
        if(!scope.matches("[A-Za-z0-9_.:-]{1,128}")||!advertise.matches("[A-Za-z0-9_.:%-]{1,253}"))throw new IllegalArgumentException("Use a simple scope and host name or IP");
        Path config=this.root.resolve("controller.properties");Properties p=AtomicProperties.read(config);
        p.putIfAbsent("id",UUID.randomUUID().toString());p.putIfAbsent("key",NetworkProtocol.newKey());p.putIfAbsent("webToken",NetworkProtocol.newKey());AtomicProperties.write(config,p);
        key=NetworkProtocol.checkKey(p.getProperty("key"));identity=new Identity(UUID.fromString(p.getProperty("id")),"HiveMindHub",NetworkProtocol.scope("server-label:"+scope));
        atlas=new NetworkAtlas(this.root);terrain=new TerrainStore(this.root);rules=new ControlRules(this.root);coordinator=new ControlRules.Coordinator(rules,identity);
        Properties bots=AtomicProperties.read(this.root.resolve("controller-bots.properties"));
        if(bots.size()>64)throw new IllegalStateException("Too many saved bots");
        for(String id:bots.stringPropertyNames()){
            String value=bots.getProperty(id);if(value.length()>100000)throw new IllegalStateException("Saved bot is oversized");
            Message message=NetworkProtocol.decode(Base64.getDecoder().decode(value));if(message instanceof State state&&state.reporter().toString().equals(id))known.put(state.reporter(),state);
        }
        Properties oldOperations=AtomicProperties.read(this.root.resolve("controller-operations.properties"));
        for(String id:oldOperations.stringPropertyNames())try{
            Message message=NetworkProtocol.decode(Base64.getDecoder().decode(oldOperations.getProperty(id)));
            if(message instanceof OperationsPackets.Snapshot snapshot&&snapshot.reporter().toString().equals(id))lastOperations.put(snapshot.reporter(),snapshot);
        }catch(RuntimeException | java.io.IOException ignored){/* Historical display state is best effort. */}
        // Prior grants can exist in clients for four seconds, plus clock skew, after a controller restart.
        teamwork=new Teamwork(System.currentTimeMillis()+4000+NetworkProtocol.CLOCK_SKEW_MS);
        List<Message> history=new ArrayList<>(atlas.snapshot(System.currentTimeMillis()));history.addAll(terrain.snapshot(System.currentTimeMillis()));
        wire=BotNetwork.host(identity,key,bind,port,history);
        try{web=new LocalNetworkViewer(webPort,actions::offer,webBind,publicUrl,p.getProperty("webToken"));web.terrain(terrain);}
        catch(Exception e){wire.close();throw e;}
        loop=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"HiveMind-controller");thread.setDaemon(false);return thread;});
        loop.scheduleWithFixedDelay(()->{try{tick(System.currentTimeMillis());}catch(Exception e){error=e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"Controller error");System.err.println(error);}},0,100,TimeUnit.MILLISECONDS);
    }
    public String url(){return web.url();}public int port(){return wire.boundPort();}public Identity identity(){return identity;}public String key(){return key;}
    private void remember(Receipt receipt){receipts.put(receipt.id()+":"+receipt.reporter(),receipt);while(receipts.size()>256)receipts.remove(receipts.keySet().iterator().next());}
    private void dispatch(Request request,long now){
        List<UUID> online=wire.states().stream().filter(state->!hivemind.utils.network.ClientRole.player(state.session())).map(State::reporter).toList();
        for(Packet packet:coordinator.handle(request,online,now)){if(packet instanceof Receipt receipt)remember(receipt);wire.publish(packet);}
    }
    private void submit(WebControlAction action,long now){
        String key=action.key();
        if(action.action()==Action.TRUST){
            try{key=UUID.fromString(key).toString();}catch(IllegalArgumentException e){
                String name=key;Set<UUID> matches=wire.states().stream().flatMap(state->state.players().entrySet().stream()).filter(entry->entry.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).collect(Collectors.toSet());
                if(matches.size()!=1)throw new IllegalArgumentException("Player is not uniquely online; use an exact UUID");key=matches.iterator().next().toString();
            }
        }
        List<UUID> expected=wire.states().stream().filter(state->!hivemind.utils.network.ClientRole.player(state.session())).map(State::reporter).filter(id->action.target()==null||action.target().equals(id)).toList();
        String description=action.action()==Action.COMMAND?action.value():action.action()+" "+key+" = "+action.value();
        pending.put(action.id(),new ControlView.History(action.id(),description,expected,now,List.of()));while(pending.size()>32)pending.remove(pending.keySet().iterator().next());
        if(now-action.time()>60000)throw new IllegalArgumentException("Action expired");
        dispatch(new Request(identity.id(),identity.name(),action.id(),action.target(),action.action(),key,action.value(),now),now);
    }
    private void tick(long now){
        if(closed)return;
        for(Message message:wire.drain(256)){
            if(message instanceof Contact c){contacts.values().removeIf(p->!p.valid(now));if(contacts.size()<512||contacts.containsKey(c.player()))contacts.put(c.player(),c);}
            else if(message instanceof State state){known.put(state.reporter(),state);while(known.size()>64)known.remove(known.keySet().iterator().next());}
            else if(message instanceof Request request)dispatch(request,now);
            else if(message instanceof Receipt receipt)remember(receipt);
            else if(message instanceof TeamPackets.Intent intent)teamwork.accept(intent,now);
            else if(!(message instanceof Packet)){atlas.accept(message,now);terrain.accept(message,now);}
        }
        WebControlAction action;for(int i=0;i<8&&(action=actions.poll())!=null;i++)try{submit(action,now);}catch(RuntimeException e){remember(new Receipt(identity.id(),identity.name(),action.id(),identity.id(),false,Objects.toString(e.getMessage(),"Action rejected"),now));}
        List<State> live=wire.states();
        for(var observation:wire.operations()){lastOperations.put(observation.reporter(),observation);while(lastOperations.size()>64)lastOperations.remove(lastOperations.keySet().iterator().next());}
        if(now-lastPlan>=500){
            Set<UUID> playing=live.stream().filter(state->Boolean.parseBoolean(state.session().getOrDefault("connected","true"))).map(State::reporter).collect(Collectors.toSet());
            wire.publish(teamwork.plan(identity,wire.peers().stream().filter(peer->playing.contains(peer.id())).toList(),now));lastPlan=now;
            for(State state:live){String command=sessions.decide(state,now);if(command!=null){UUID id=UUID.randomUUID();pending.put(id,new ControlView.History(id,"Automatic: #"+command,List.of(state.reporter()),now,List.of()));while(pending.size()>32)pending.remove(pending.keySet().iterator().next());wire.publish(new Execute(identity.id(),identity.name(),id,identity.id(),state.reporter(),command,now));}}
        }
        if(now-lastDefaults>=2000){
            wire.publish(new Defaults(identity.id(),identity.name(),rules.version(),rules.settings(null),rules.trust(null),now));
            for(State state:live)if(!hivemind.utils.network.ClientRole.player(state.session())&&state.policyVersion()!=rules.version()&&now-synced.getOrDefault(state.reporter(),0L)>=5000){wire.publish(rules.policy(identity,UUID.randomUUID(),identity.id(),state.reporter(),now));synced.put(state.reporter(),now);}
            lastDefaults=now;
        }
        if(now-lastPage>=500){
            web.snapshot(ObservationView.json(atlas.json(now,wire.status()+(error.isEmpty()?"":"; "+error),"minecraft:overworld",wire.peers()),contacts.values(),now));
            List<ControlView.History> history=new ArrayList<>();for(var request:pending.values())history.add(new ControlView.History(request.id(),request.description(),request.expected(),request.time(),receipts.values().stream().filter(receipt->receipt.id().equals(request.id())).toList()));Collections.reverse(history);
            String join="#network controller "+advertise+" "+wire.boundPort()+" "+key+" "+scope;
            web.controls(ControlView.withOperations(ControlView.json(identity,true,true,key,known.values().stream().sorted(Comparator.comparing(State::reporterName)).toList(),live.stream().map(State::reporter).collect(Collectors.toSet()),rules.settings(null),rules.trust(null),history,
                Map.of("standalone","true","scope",scope,"joinCommand",join,"version",Main.VERSION)),lastOperations.values()));
            lastPage=now;
        }
        if(now-lastSave>=30000){save();lastSave=now;}
    }
    private void save(){atlas.save();terrain.save();Properties p=new Properties(),observations=new Properties();try{for(State state:known.values())p.setProperty(state.reporter().toString(),Base64.getEncoder().encodeToString(NetworkProtocol.encode(state)));for(var snapshot:lastOperations.values())observations.setProperty(snapshot.reporter().toString(),Base64.getEncoder().encodeToString(NetworkProtocol.encode(snapshot)));}catch(java.io.IOException e){throw new IllegalStateException(e);}AtomicProperties.write(root.resolve("controller-bots.properties"),p);AtomicProperties.write(root.resolve("controller-operations.properties"),observations);}
    @Override public void close(){if(closed)return;closed=true;loop.shutdown();try{if(!loop.awaitTermination(5,TimeUnit.SECONDS))loop.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();}try{save();}finally{wire.close();web.close();}}
}

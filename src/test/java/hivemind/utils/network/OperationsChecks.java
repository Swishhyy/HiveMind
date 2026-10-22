/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.network.NetworkProtocol.*;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.network.TeamPackets.*;
import java.nio.file.*;
import java.util.*;
public final class OperationsChecks {
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid mutation was accepted");}catch(IllegalArgumentException|IllegalStateException expected){}}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Operations checks passed: durable ordered queues, pause/restart isolation, navigation progress, disjoint search sectors, idle assistance and live evidence/packet boundaries");}
    public static void runAll()throws Exception{
        Path root=Files.createTempDirectory("hivemind-itinerary");Endpoint point=new Endpoint("minecraft:overworld",-100,64,200);
        try{
            TaskQueue queue=new TaskQueue(root);var a=queue.add("explore",point,256,false);var b=queue.add("check",point,0,false);var c=queue.add("return",point,0,false);
            queue.move(c.id(),1);queue.pause(true);queue=new TaskQueue(root);check(queue.paused()&&queue.tasks().equals(List.of(c,a,b)),"Reordering and pause must survive restart");queue.remove(a.id());queue.pause(false);queue.complete(c.id());check(new TaskQueue(root).first().equals(b),"Completion must advance exactly once and persist");queue.complete(c.id());check(queue.first().equals(b),"Repeated completion may not remove another task");
            TaskQueue q=queue;rejects(()->q.add("explore",point,4096,true));rejects(()->q.move(b.id(),0));rejects(()->q.remove(999));check(new TaskQueue(root).first().equals(b),"Invalid edits must preserve the itinerary");
            queue.clear();for(int i=0;i<32;i++)queue.add("go",point,0,false);rejects(()->q.add("go",point,0,false));check(queue.tasks().size()==32,"Queue cap lost existing work");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        RecoveryPolicy progress=new RecoveryPolicy();long now=100000;
        for(int i=0;i<100;i++)check(progress.sample("watch",0,0,false,now+i*1000)==RecoveryPolicy.Action.NONE,"Intentional watching must never trigger recovery");
        check(progress.sample("travel",0,0,true,now)==RecoveryPolicy.Action.NONE,"A new route needs a progress window");check(progress.sample("travel",0,0,true,now+15000)==RecoveryPolicy.Action.REPATH,"First stall should replan");check(progress.sample("travel",0,0,true,now+30000)==RecoveryPolicy.Action.SIDESTEP,"Repeated stall should detour");check(progress.sample("travel",0,0,true,now+45000)==RecoveryPolicy.Action.WALK,"Repeated failed takeoffs should fall back to walking");
        for(int i=1;i<30;i++)check(progress.sample("walking",i*10,0,true,now+45000+i*1000)==RecoveryPolicy.Action.NONE,"Actual forward progress must not be called stuck");
        Identity host=new Identity(UUID.randomUUID(),"Hub",NetworkProtocol.scope("operations"));UUID target=UUID.randomUUID();List<Intent> intents=new ArrayList<>();Map<UUID,String> members=new HashMap<>();members.put(host.id(),host.name());for(int i=0;i<4;i++){UUID id=UUID.randomUUID();members.put(id,"Bot"+i);intents.add(new Intent(id,"Bot"+i,Kind.HUNT,target,point,null,false,false,now));}Plan plan=new Plan(host.id(),host.name(),members,intents,Map.of(),now);
        for(int degrees=0;degrees<360;degrees++){double angle=degrees*Math.PI/180;int owners=0;for(var bot:intents)if(Teamwork.searchSector(plan,Kind.HUNT,target,point.dimension(),bot.reporter(),Math.cos(angle)*100,Math.sin(angle)*100))owners++;check(owners==1,"Every search direction must have exactly one owner");}
        now=System.currentTimeMillis();List<State> states=new ArrayList<>();List<OperationsPackets.Snapshot> ops=new ArrayList<>();for(int i=0;i<4;i++){var id=intents.get(i).reporter();var p=new Endpoint(point.dimension(),point.x()+i*20,64,point.z());states.add(new State(id,"Bot"+i,"Idle",20,20,p,Map.of(),Map.of(),Map.of(),0,now,Map.of(),Map.of("connected","true","role",i==0?"player":"bot")));ops.add(new OperationsPackets.Snapshot(id,"Bot"+i,Map.of("available",i==1?"false":"true"),List.of(),false,now));}
        check(AssistancePolicy.choose(states,ops,point,now).equals(intents.get(2).reporter()),"Assistance must skip human and busy clients before choosing the closest idle bot");final long at=now;rejects(()->AssistancePolicy.choose(states,ops,point,at+21000));
        var task=new TaskQueue.Task(1,"explore",point,256);var snapshot=new OperationsPackets.Snapshot(host.id(),host.name(),Map.of("task","Explore","blocker","Restocking"),List.of(task),false,now);var obstacle=new OperationsPackets.Obstacle(host.id(),host.name(),point,"Failed takeoff",now+60000,now);var ping=new OperationsPackets.Ping(host.id(),host.name(),123,true,now);
        for(Message message:List.of(snapshot,obstacle,ping)){check(NetworkProtocol.decode(NetworkProtocol.encode(message)).equals(message),"Operations codec changed a message");check(!new NetworkAtlas().accept(message,now),"Operational data must never become historical observation evidence");}
        check(OperationsPackets.json(List.of(snapshot)).contains("\"tasks\":[{\"id\":1"),"Dashboard must contain the actual queue");
        var contact=new Contact(host.id(),host.name(),target,"Target",point,now);String atlas=new NetworkAtlas().json(now,"test",point.dimension(),List.of());String view=ObservationView.json(atlas,List.of(contact),now);check(view.contains("\"evidence\":\"esp\"")&&!ObservationView.json(atlas,List.of(contact),now+7000).contains("Target"),"ESP hints must be distinguished and expire independently of visual history");
        check(ClientRole.localAllowed("backup")&&ClientRole.localAllowed("bring")&&!ClientRole.localAllowed("assist"),"Human clients may request help but must not accept automation");
    }
}

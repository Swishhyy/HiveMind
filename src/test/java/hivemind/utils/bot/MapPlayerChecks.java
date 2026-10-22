/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.utils.network.*;
import hivemind.utils.network.ControlPackets.Contact;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.*;
import java.util.*;
public final class MapPlayerChecks {
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid order accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Map/player checks passed: finite dimension-pinned surveys, coordinate bounds, persistent manual role, command guards and transient contact codec/expiry");}
    private static ControlPackets.State node(UUID id,String name,String role,boolean online,long now){return new ControlPackets.State(id,name,"Ready",20,20,new Endpoint("minecraft:overworld",0,64,0),Map.of(),Map.of(),Map.of(),0,now,Map.of(),Map.of("role",role,"online",""+online));}
    public static void runAll()throws Exception{
        long sample=100000;UUID mike=UUID.randomUUID();ControlPackets.State bot=node(mike,"SeizureMike","bot",true,sample),manual=node(UUID.randomUUID(),"Dylan","player",true,sample),offline=node(UUID.randomUUID(),"Offline","bot",false,sample),stale=node(UUID.randomUUID(),"Old","bot",true,sample-11000);
        var nodes=List.of(bot,manual,offline,stale);check(PlayerBotOrders.targets(nodes,"all",sample).equals(List.of(bot)),"Player commands must skip manual clients, offline clients and stale telemetry");
        check(PlayerBotOrders.targets(nodes,"seizuremike",sample).equals(List.of(bot))&&PlayerBotOrders.targets(nodes,mike.toString(),sample).equals(List.of(bot)),"Select an exact live bot by name or UUID");rejects(()->PlayerBotOrders.targets(nodes,"Dylan",sample));rejects(()->PlayerBotOrders.targets(nodes,"Missing",sample));
        rejects(()->PlayerBotOrders.targets(List.of(bot,node(UUID.randomUUID(),"SeizureMike","bot",true,sample)),"SeizureMike",sample));
        var assigned=new Endpoint("minecraft:the_nether",-101,70,202);check(PlayerBotOrders.base(assigned,16).equals("botbase set minecraft:the_nether -101 70 202 16"),"A base command must pin the issuing player's coordinates and dimension, not each receiving bot's feet");
        check(PlayerBotOrders.chest("remove",assigned,null).equals("deposit point remove minecraft:the_nether -101 70 202"),"Removing shared storage must use its endpoint, not potentially different per-bot storage IDs");
        check(PlayerBotOrders.chest("add",assigned,"supplies").endsWith("202 supplies"),"Supply rules must travel with the selected endpoint");rejects(()->PlayerBotOrders.base(assigned,33));rejects(()->PlayerBotOrders.chest("clear",assigned,null));rejects(()->PlayerBotOrders.chest("remove",assigned,"supplies"));rejects(()->PlayerBotOrders.chest("add",assigned,"items"));
        check(TrustedControl.command("#"+PlayerBotOrders.base(assigned,16))!=null&&TrustedControl.command("#"+PlayerBotOrders.chest("add",assigned,"supplies"))!=null,"Generated endpoint commands must pass host validation");
        check(ClientRole.localAllowed("bots")&&!ClientRole.localAllowed("storagepoint")&&!ClientRole.remoteAllowed("bots")&&TrustedControl.command("#bots all base set")==null,"Player configuration may route outward, never enable self automation or recursively fan out remote commands");

        for(int radius:new int[]{32,64,256,2048}){
            var order=new MapOrder("explore","minecraft:overworld",-100,150,radius);var points=order.waypoints(128);
            check(points.get(0).equals(new SurveyPolicy.Offset(-100,150)),"Survey must start at requested center");
            check(new HashSet<>(points).size()==points.size()&&points.size()>1&&points.size()<14000,"Survey must be bounded, unique and visit beyond the center");
            for(var p:points)check(Math.hypot(p.x()+100,p.z()-150)<=radius,"Survey escaped its requested radius");
        }
        check(new MapOrder("go","minecraft:the_nether",30000000,-30000000,0).waypoints(64).size()==1,"Go must have one destination");
        rejects(()->new MapOrder("go","bad",0,0,0));rejects(()->new MapOrder("go","minecraft:overworld",30000001,0,0));
        rejects(()->new MapOrder("explore","minecraft:overworld",0,0,31));rejects(()->new MapOrder("explore","minecraft:overworld",0,0,2049));rejects(()->new MapOrder("go","minecraft:overworld",0,0,256));
        Path root=Files.createTempDirectory("hivemind-player");try{
            ClientRole role=new ClientRole(root);check(!role.player(),"Existing clients must keep bot behavior");role.set("player");check(new ClientRole(root).player(),"Manual role lost on restart");
            for(String label:List.of("mine","scout","kill","session","resume","elytra"))check(!ClientRole.localAllowed(label)&&!ClientRole.remoteAllowed(label),"Player mode allowed automation: "+label);
            check(ClientRole.localAllowed("client")&&ClientRole.localAllowed("network"),"Manual clients must be able to configure sharing or switch modes");
            ClientRole locked=new ClientRole(root,"player");check(locked.locked()&&locked.player(),"Player package must ignore an existing bot mode");rejects(()->locked.set("bot"));rejects(()->locked.requireRole("bot"));check(locked.player(),"Rejected mode switch must leave manual sharing active");locked.set("player");
            rejects(()->new ClientRole(root,"bad"));
            rejects(()->role.set("all"));check(new ClientRole(root).player(),"Bad input changed role");role.set("bot");check(!new ClientRole(root).player(),"Local opt-in did not enable bot mode");check(new ClientRole(root,"player").player(),"Player lock must survive a saved bot role");role.set("player");check(new ClientRole(root,"bot").player(),"Standard-jar updates must preserve existing manual roles");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        long now=System.currentTimeMillis();var contact=new Contact(UUID.randomUUID(),"Player",UUID.randomUUID(),"Target",new Endpoint("minecraft:overworld",-17,70,32),now);
        check(!new NetworkAtlas().accept(contact,now),"ESP detections must not become persistent sightings or hotspot evidence");
        check(NetworkProtocol.decode(NetworkProtocol.encode(contact)).equals(contact),"Contact packet did not round trip");check(contact.valid(now+5999)&&!contact.valid(now+6001),"ESP contacts must expire quickly");
        check(TrustedControl.command("#scout explore minecraft:overworld -100 150 256")!=null&&TrustedControl.command("#client bot")==null,"Only local commands may change the role");
    }
}

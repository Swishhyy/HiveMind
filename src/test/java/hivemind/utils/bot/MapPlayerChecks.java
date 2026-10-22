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
    public static void runAll()throws Exception{
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

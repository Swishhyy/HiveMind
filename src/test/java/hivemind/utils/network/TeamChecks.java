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

package hivemind.utils.network;

import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.network.NetworkProtocol.*;
import hivemind.utils.network.TeamPackets.*;
import hivemind.utils.network.TerrainPackets.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class TeamChecks {
    private static final String SCOPE=NetworkProtocol.scope("team-test");
    private static final Identity HOST=who("Host"),A=who("Alpha"),B=who("Beta");
    private static Identity who(String name){return new Identity(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),name,SCOPE);}
    private static Endpoint point(int x){return new Endpoint("minecraft:overworld",x,64,0);}
    private static Intent work(Identity id,Kind kind,Endpoint work,boolean blast,long time){return new Intent(id.id(),id.name(),kind,HOST.id(),point(0),work,blast,false,time);}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void await(BooleanSupplier condition,String reason)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError(reason);Thread.sleep(10);}}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Team/terrain checks passed: exclusive vein claims, release/disconnect grace, roles, ally strike spacing, autonomy, terrain persistence/replay, authenticated tile API");}
    public static void runAll()throws Exception{leases();terrain();relay();http();}
    private static void leases()throws Exception{
        long now=System.currentTimeMillis();Teamwork team=new Teamwork();List<Identity> online=List.of(A,B);
        team.accept(work(A,Kind.MINE,point(12),false,now),now);team.accept(work(B,Kind.MINE,point(13),false,now),now);
        Plan plan=team.plan(HOST,online,now);check(plan.leases().size()==1,"A contested vein has exactly one owner");
        Teamwork restarting=new Teamwork(now+14000);restarting.accept(work(A,Kind.MINE,point(12),false,now),now);check(restarting.plan(HOST,List.of(A),now).leases().isEmpty(),"A fresh host waits out old grants before assigning contested resources");
        check(!plan.valid(now+4001),"A missing host cannot keep stale team plans live");
        UUID holder=plan.leases().keySet().iterator().next();Identity owner=holder.equals(A.id())?A:B,other=holder.equals(A.id())?B:A;
        check(NetworkProtocol.decode(NetworkProtocol.encode(plan)).equals(plan),"Plan codec preserves roster, intents and lease expiry");
        check(NetworkProtocol.decode(NetworkProtocol.encode(work(A,Kind.MINE,point(12),false,now))).equals(work(A,Kind.MINE,point(12),false,now)),"Intent codec preserves provenance");
        team.accept(work(other,Kind.MINE,point(32),false,now+1),now+1);check(team.plan(HOST,online,now+1).leases().size()==2,"Separate veins can be worked in parallel");
        team.accept(work(owner,Kind.IDLE,null,false,now+2),now+2);plan=team.plan(HOST,online,now+2);check(!plan.leases().containsKey(owner.id()),"Explicit task completion releases a claim immediately");
        Teamwork lost=new Teamwork();lost.accept(work(A,Kind.MINE,point(12),false,now),now);lost.plan(HOST,List.of(A),now);
        lost.accept(work(B,Kind.MINE,point(12),false,now+5000),now+5000);
        check(!lost.plan(HOST,List.of(B),now+5000).leases().containsKey(B.id()),"A disconnect retains the old holder's clock-skew grace period");
        lost.accept(work(B,Kind.MINE,point(12),false,now+15001),now+15001);check(lost.plan(HOST,List.of(B),now+15001).leases().containsKey(B.id()),"An abandoned claim becomes available after expiry and grace");
        Plan roles=new Plan(HOST.id(),HOST.name(),Map.of(HOST.id(),HOST.name(),A.id(),A.name(),B.id(),B.name()),List.of(work(A,Kind.HUNT,point(0),true,now),work(B,Kind.HUNT,point(0),true,now)),Map.of(),now);
        UUID leader=Teamwork.blastLeader(roles,HOST.id(),"minecraft:overworld");check(leader!=null&&leader.equals(Teamwork.blastLeader(roles,HOST.id(),"minecraft:overworld")),"Hunters agree on one explosive lead");
        check(Teamwork.blastLeader(roles,HOST.id(),"minecraft:the_nether")==null,"Hunt roles never cross dimensions");
        check(!Teamwork.clearStrike(0,0,3,0,1,0,false),"Do not strike through an ally");
        check(!Teamwork.clearStrike(0,0,3,0,3,2,true)&&Teamwork.clearStrike(0,0,3,0,3,2,false),"Sword sweep spacing is wider than an ordinary strike");
        check(!RoamPolicy.eligible(true,false,true,false,false,false,true),"Saved tracking orders must reserve the bot through respawn/retry delays, even without an active path");
        check(RoamPolicy.eligible(true,false,true,false,false,false,false),"Clearing the saved tracking order releases background roaming");
        check(RoamPolicy.eligible(true,false,true,false,false,false),"Idle live bots scout by default");
        check(!RoamPolicy.eligible(true,true,true,false,false,false)&&!RoamPolicy.eligible(true,false,true,true,false,false)&&!RoamPolicy.eligible(true,false,true,false,true,false),"Stop, foreground work and pending jobs override roaming");
        check(RoamPolicy.frontierScore(false,64,0,false)<RoamPolicy.frontierScore(true,64,0,false)&&RoamPolicy.frontierScore(false,64,0,false)<RoamPolicy.frontierScore(false,64,0,true),"Scouts prefer unseen terrain away from another scout's destination");
        check(hivemind.utils.social.TraversalPolicy.evidencePenalty(true,false,true,80)==80&&hivemind.utils.social.TraversalPolicy.evidencePenalty(true,true,true,80)==0,"Quiet stalks penalize terrain edits; hunts do not inherit that penalty");
        check(hivemind.utils.social.TraversalPolicy.armHeight(4.5,-3.9)>12,"Fast falls aim before the one-tick bucket placement window");
        check(hivemind.utils.social.TraversalPolicy.bucketLanding(true,true,true,"stone")&&!hivemind.utils.social.TraversalPolicy.bucketLanding(false,true,true,"stone")&&!hivemind.utils.social.TraversalPolicy.bucketLanding(true,true,true,"chest")&&!hivemind.utils.social.TraversalPolicy.bucketLanding(true,false,true,"stone"),"Bucket falls reject water-disabled dimensions, containers and obstructed landings");
        check(hivemind.utils.social.TraversalPolicy.bucketHeight(50000)==1024,"Bucket height is bounded");
    }
    private static Tile tile(long now){return new Tile(A.id(),A.name(),"minecraft:overworld",-2,3,"639844".repeat(256),"0040".repeat(256),now);}
    private static void terrain()throws Exception{
        long now=System.currentTimeMillis();Path root=Files.createTempDirectory("team-terrain-");
        try{
            TerrainStore store=new TerrainStore(root);Tile tile=tile(now);Site site=new Site(A.id(),A.name(),point(-32),"Possible base","Visible chest; unverified",false,now);
            for(Message message:List.of(tile,site)){check(NetworkProtocol.decode(NetworkProtocol.encode(message)).equals(message),"Terrain and site packets round-trip");check(!new NetworkAtlas().accept(message,now),"Terrain does not inflate visited-chunk or player history");check(store.accept(message,now),"Terrain/site accepted");check(!store.accept(message,now),"Replay is idempotent");}
            store.save();TerrainStore loaded=new TerrainStore(root);check(loaded.snapshot(now).containsAll(List.of(tile,site)),"Terrain and sites survive restart");
            check(loaded.tile("minecraft:overworld",-2,3).height(0,0)==64&&loaded.tile("minecraft:the_nether",-2,3)==null,"Tile heights and dimension isolation survive storage");
            Site confirmed=new Site(A.id(),A.name(),point(-32),"Confirmed base","Controller confirmed",true,now+1);loaded.accept(confirmed,now+1);
            loaded.accept(new Site(B.id(),B.name(),point(-32),"Possible base","Visible furnace",false,now+60001),now+60001);
            check(loaded.index(now+60001).contains("Confirmed base"),"A weak automatic mark cannot replace a manual confirmation");
            check(new TerrainStore(root.resolve("other-server")).snapshot(now).isEmpty(),"Terrain is isolated between Minecraft servers");
            try{new Tile(A.id(),A.name(),"minecraft:overworld",0,0,"00","0040".repeat(256),now);throw new AssertionError("Short tile accepted");}catch(IllegalArgumentException expected){}
            try{new Tile(A.id(),A.name(),"minecraft:overworld",Integer.MAX_VALUE,0,"639844".repeat(256),"0040".repeat(256),now);throw new AssertionError("Unbounded tile coordinate accepted");}catch(IllegalArgumentException expected){}
            check(new TerrainStore().accept(tile(now-365L*86400000),now),"Explored terrain must survive the age limit used for player sightings");
            check(!new TerrainStore().accept(tile(now+NetworkProtocol.CLOCK_SKEW_MS+1),now),"Durable terrain must still reject timestamps beyond allowed clock skew");
            Path legacyRoot=root.resolve("legacy");Properties legacy=new Properties();legacy.setProperty("count","1");legacy.setProperty("entry.0",Base64.getEncoder().encodeToString(NetworkProtocol.encode(tile(now-365L*86400000))));
            hivemind.utils.bot.AtomicProperties.write(legacyRoot.resolve("bot-network-terrain.properties"),legacy);
            TerrainStore migrating=new TerrainStore(legacyRoot);migrating.save();
            check(new TerrainStore(legacyRoot).tile("minecraft:overworld",-2,3)!=null,"Legacy terrain must migrate to region archives without losing historical chunks");
            String rgb="639844".repeat(256),heights="0040".repeat(256);
            for(int x=0;x<4100;x++)loaded.accept(new Tile(A.id(),A.name(),"minecraft:overworld",x,-40,rgb,heights,now+2),now+2);
            check(loaded.tile("minecraft:overworld",0,-40)!=null&&loaded.tile("minecraft:overworld",4099,-40)!=null,"New exploration must not evict old terrain after 4096 chunks");
            loaded.save();TerrainStore archive=new TerrainStore(root);
            check(archive.tile("minecraft:overworld",0,-40)!=null&&archive.tile("minecraft:overworld",4099,-40)!=null,"Compressed region archives must retain all explored areas across restart");
            check(archive.index(now+366L*86400000).contains("\"x\":4099"),"A distant historical tile must remain in the map index without a bot currently there");
            check(archive.index(now+366L*86400000).contains("\"c\":6527044,\"y\":65.0"),"The whole-map overview must include color and height without fetching every detail tile");
            archive.clear();archive.save();check(new TerrainStore(root).snapshot(now+2).isEmpty(),"An explicit history clear must remove archived regions too");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void relay()throws Exception{
        String key=NetworkProtocol.newKey();try(BotNetwork host=BotNetwork.host(HOST,key,"127.0.0.1",0)){
            await(()->host.boundPort()>0,"Relay did not start");try(BotNetwork a=BotNetwork.join(A,key,"127.0.0.1",host.boundPort());BotNetwork b=BotNetwork.join(B,key,"127.0.0.1",host.boundPort())){
                await(()->host.peers().size()==2&&a.connected()&&b.connected(),"Team peers did not join on both ends");long now=System.currentTimeMillis();Intent intent=work(A,Kind.MINE,point(12),false,now);a.publish(intent);List<Message> inbox=new ArrayList<>();
                await(()->{inbox.addAll(host.drain(128));return inbox.contains(intent);},"Intent failed to reach host");
                Teamwork coordinator=new Teamwork();coordinator.accept(intent,now);Plan plan=coordinator.plan(HOST,List.of(A,B),System.currentTimeMillis());check(host.publish(plan),"Host can publish assignments");List<Message> received=new ArrayList<>();
                await(()->{received.addAll(b.drain(128));return received.contains(plan);},"Another bot did not receive the same plan");
                Plan forgery=new Plan(A.id(),A.name(),Map.of(A.id(),A.name()),List.of(),Map.of(),System.currentTimeMillis());check(!a.publish(forgery),"A client cannot publish a forged roster/assignment");
                Tile tile=tile(System.currentTimeMillis());a.publish(tile);await(()->{received.addAll(b.drain(128));return received.contains(tile);},"Terrain tile did not relay between bots");
                Marker marker=new Marker(A.id(),A.name(),UUID.randomUUID(),"logout",point(42),"Host logout","Last visible location",HOST.id(),now-1000,false,now);a.publish(marker);await(()->{received.addAll(b.drain(128));return received.contains(marker);},"Logout marker did not relay between bots");
                try(BotNetwork late=BotNetwork.join(who("Late"),key,"127.0.0.1",host.boundPort())){List<Message> history=new ArrayList<>();await(()->{history.addAll(late.drain(128));return history.contains(tile)&&history.contains(marker);},"Terrain and markers did not replay to late join");check(history.stream().noneMatch(p->p instanceof Plan||p instanceof Intent),"Stale task assignments and claims never replay as history");}
            }
        }
    }
    private static void http()throws Exception{
        TerrainStore terrain=new TerrainStore();terrain.accept(tile(System.currentTimeMillis()),System.currentTimeMillis());
        try(LocalNetworkViewer viewer=new LocalNetworkViewer(0)){
            viewer.terrain(terrain);String url=viewer.url(),token=url.substring(url.indexOf('#')+1),base=url.substring(0,url.indexOf('#'));HttpClient client=HttpClient.newHttpClient();
            String query="api/tile?d=minecraft%3Aoverworld&x=-2&z=3";
            check(client.send(HttpRequest.newBuilder(URI.create(base+query)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==401,"Tiles require the page token");
            var response=client.send(HttpRequest.newBuilder(URI.create(base+query)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==200&&response.body().contains("639844"),"Authenticated tile API returns sampled colors");
            var compressed=client.send(HttpRequest.newBuilder(URI.create(base+query)).header("Authorization","Bearer "+token).header("Accept-Encoding","gzip").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            check(compressed.statusCode()==200&&compressed.headers().firstValue("Content-Encoding").orElse("").equals("gzip"),"Terrain details support compressed browser transfer");
            try(var in=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed.body()))){check(new String(in.readAllBytes(),StandardCharsets.UTF_8).contains("639844"),"Compressed responses retain the same terrain payload");}
            check(client.send(HttpRequest.newBuilder(URI.create(base+query+"&x=4")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==400,"Duplicate tile coordinates are rejected");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"api/terrain")).header("Authorization","Bearer "+token).header("Origin","https://unrelated.example").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==403,"An unrelated origin cannot read terrain");
        }
    }
}

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

package baritone.utils.network;

import baritone.utils.mining.WorldRoutes.Endpoint;
import baritone.utils.network.NetworkProtocol.*;
import baritone.utils.network.TeamPackets.*;
import baritone.utils.network.TerrainPackets.*;
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
        check(RoamPolicy.eligible(true,false,true,false,false,false),"Idle live bots scout by default");
        check(!RoamPolicy.eligible(true,true,true,false,false,false)&&!RoamPolicy.eligible(true,false,true,true,false,false)&&!RoamPolicy.eligible(true,false,true,false,true,false),"Stop, foreground work and pending jobs override roaming");
        check(RoamPolicy.frontierScore(false,64,0,false)<RoamPolicy.frontierScore(true,64,0,false)&&RoamPolicy.frontierScore(false,64,0,false)<RoamPolicy.frontierScore(false,64,0,true),"Scouts prefer unseen terrain away from another scout's destination");
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
            check(!new TerrainStore().accept(tile(now-31L*86400000),now),"Old terrain expires");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void relay()throws Exception{
        String key=NetworkProtocol.newKey();try(BotNetwork host=BotNetwork.host(HOST,key,"127.0.0.1",0)){
            await(()->host.boundPort()>0,"Relay did not start");try(BotNetwork a=BotNetwork.join(A,key,"127.0.0.1",host.boundPort());BotNetwork b=BotNetwork.join(B,key,"127.0.0.1",host.boundPort())){
                await(()->host.peers().size()==2,"Team peers did not join");long now=System.currentTimeMillis();Intent intent=work(A,Kind.MINE,point(12),false,now);a.publish(intent);List<Message> inbox=new ArrayList<>();
                await(()->{inbox.addAll(host.drain(128));return inbox.contains(intent);},"Intent failed to reach host");
                Teamwork coordinator=new Teamwork();coordinator.accept(intent,now);Plan plan=coordinator.plan(HOST,List.of(A,B),System.currentTimeMillis());check(host.publish(plan),"Host can publish assignments");List<Message> received=new ArrayList<>();
                await(()->{received.addAll(b.drain(128));return received.contains(plan);},"Another bot did not receive the same plan");
                Plan forgery=new Plan(A.id(),A.name(),Map.of(A.id(),A.name()),List.of(),Map.of(),System.currentTimeMillis());check(!a.publish(forgery),"A client cannot publish a forged roster/assignment");
                Tile tile=tile(System.currentTimeMillis());a.publish(tile);await(()->{received.addAll(b.drain(128));return received.contains(tile);},"Terrain tile did not relay between bots");
                try(BotNetwork late=BotNetwork.join(who("Late"),key,"127.0.0.1",host.boundPort())){List<Message> history=new ArrayList<>();await(()->{history.addAll(late.drain(128));return history.contains(tile);},"Terrain did not replay to late join");check(history.stream().noneMatch(p->p instanceof Plan||p instanceof Intent),"Stale task assignments and claims never replay as history");}
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
            check(client.send(HttpRequest.newBuilder(URI.create(base+query+"&x=4")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==400,"Duplicate tile coordinates are rejected");
            check(client.send(HttpRequest.newBuilder(URI.create(base+"api/terrain")).header("Authorization","Bearer "+token).header("Origin","https://unrelated.example").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==403,"An unrelated origin cannot read terrain");
        }
    }
}

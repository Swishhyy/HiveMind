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

package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.network.*;
import hivemind.utils.network.TerrainPackets.Marker;
import java.nio.file.*;
import java.util.*;

public final class AutonomyChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Autonomy checks passed: durable offline orders, cancellation/restart, marker tombstones/provenance, logout evidence, flight reserves and private report expiry");}
    public static void runAll()throws Exception{
        long now=System.currentTimeMillis();UUID bot=UUID.randomUUID(),target=UUID.randomUUID(),owner=UUID.randomUUID(),id=UUID.randomUUID();Path root=Files.createTempDirectory("bot-autonomy");
        try{
            TrackingOrderStore store=new TrackingOrderStore(root);
            var pending=new TrackingOrderStore.Order("OfflinePlayer",null,true,"auto",owner,now);store.set(pending);
            check(new TrackingOrderStore(root).order().equals(pending),"A never-seen offline player can be retained across a client restart without inventing a UUID");
            var resolved=pending.resolved(target,"OfflinePlayer");store.set(resolved);store.pause(true);
            var reloaded=new TrackingOrderStore(root);check(reloaded.paused()&&reloaded.order().equals(resolved),"Resolved identity, owner, combat mode and pause survive reload");
            check(new TrackingOrderStore(root.resolve("other-server")).order()==null,"Orders cannot cross Minecraft servers");
            store.clear();check(new TrackingOrderStore(root).order()==null&&!new TrackingOrderStore(root).paused(),"Explicit stop cannot resurrect a saved hunt after restart");
            try{new TrackingOrderStore.Order("../not-a-player",null,true,"auto",owner,now);throw new AssertionError("Invalid name accepted");}catch(IllegalArgumentException expected){}
            Marker mark=new Marker(bot,"Scout",id,"logout",new Endpoint("minecraft:overworld",120,70,-40),"OfflinePlayer logout","Last visible location, not an exact logout block",target,now-5000,false,now);
            check(NetworkProtocol.decode(NetworkProtocol.encode(mark)).equals(mark),"Marker codec preserves observation time separately from logout time");
            TerrainStore terrain=new TerrainStore(root);check(terrain.accept(mark,now),"Logout mark accepted");
            Marker removed=new Marker(bot,"Scout",id,mark.kind(),mark.point(),mark.label(),mark.detail(),target,mark.seenAt(),true,now+1);
            check(terrain.accept(removed,now+1)&&terrain.markers(now+1).isEmpty(),"Deleted marks are hidden while their tombstone is retained");
            check(!terrain.accept(mark,now+1),"Replayed old sightings cannot resurrect a deleted mark");
            terrain.save();TerrainStore loaded=new TerrainStore(root);check(loaded.markers(now+1).isEmpty()&&loaded.snapshot(now+1).contains(removed),"Deletion survives restart and is available for late-join replay");
            Marker other=new Marker(owner,"OtherBot",id,"waypoint",mark.point(),"Other waypoint","Independent owner",null,now,false,now);
            check(loaded.accept(other,now)&&loaded.markers(now).size()==1,"One reporter's tombstone cannot remove another reporter's identically numbered mark");
            check(loaded.index(now).contains("Other waypoint")&&!loaded.index(now).contains("OfflinePlayer logout"),"Map API excludes deleted markers");
            check(!new NetworkAtlas().accept(mark,now),"Markers are not counted as visited chunks or visual player reports");
            check(AutonomyPolicy.logoutEvidence(true,false,true,now-1000,now),"Online-to-offline transition plus a fresh visual observation is evidence");
            check(!AutonomyPolicy.logoutEvidence(true,true,true,now-1000,now),"Leaving client render distance alone is not logout evidence");
            check(!AutonomyPolicy.logoutEvidence(true,false,false,now-1000,now),"Disconnecting the bot cannot manufacture target logout spots");
            check(!AutonomyPolicy.logoutEvidence(true,false,true,now-120001,now),"Old sightings cannot be presented as fresh logout locations");
            check(AutonomyPolicy.mayFly(true,true,true,200,30,8,600,256,false,false),"A supplied long-distance scouting leg can fly");
            check(!AutonomyPolicy.mayFly(true,true,true,200,9,8,600,256,false,false),"Rockets reserved for landing are not budgeted for departure");
            check(!AutonomyPolicy.mayFly(true,true,true,20,30,8,600,256,false,false),"A nearly broken elytra cannot start an autonomous flight");
            check(!AutonomyPolicy.mayFly(true,true,true,200,30,8,600,256,true,false),"Close observation/combat is not a flight leg");
            check(!AutonomyPolicy.mayFly(true,false,true,200,30,8,600,256,false,false),"Unsupported native platforms stay on foot");
            check(AutonomyPolicy.mayReport(true,true,now,now-1000),"An online trusted controller receives a fresh report");
            check(!AutonomyPolicy.mayReport(false,true,now,now-1000)&&!AutonomyPolicy.mayReport(true,false,now,now-1000)&&!AutonomyPolicy.mayReport(true,true,now,now-180001),"Revoked, offline and stale report recipients are dropped at send time");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}

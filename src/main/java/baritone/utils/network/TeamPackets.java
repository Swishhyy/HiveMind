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
import baritone.utils.network.ControlPackets.Packet;
import java.io.*;
import java.util.*;
import static baritone.utils.network.ControlPackets.*;

/** Ephemeral team intent and host assignments; never part of observation history. */
public final class TeamPackets {
    public enum Kind { IDLE, MINE, STALK, HUNT, ROAM }
    public record Intent(UUID reporter,String reporterName,Kind kind,UUID target,Endpoint point,Endpoint work,
                         boolean explosive,boolean engaged,long observedAt) implements Packet {
        public Intent { base(reporter,reporterName);Objects.requireNonNull(kind);Objects.requireNonNull(point); }
    }
    public record Lease(Endpoint point,long until) { public Lease { Objects.requireNonNull(point); } }
    public record Plan(UUID reporter,String reporterName,Map<UUID,String> members,List<Intent> intents,Map<UUID,Lease> leases,long observedAt) implements Packet {
        public Plan {
            base(reporter,reporterName);members=uuidMap(members,64,16);intents=List.copyOf(intents);leases=Map.copyOf(leases);
            if(intents.size()>17||leases.size()>64||!members.containsKey(reporter))throw new IllegalArgumentException("Invalid team plan");
            Set<UUID> unique=new HashSet<>();
            for(Intent intent:intents)if(!members.containsKey(intent.reporter())||!unique.add(intent.reporter()))throw new IllegalArgumentException("Invalid team intent");
            for(Lease lease:leases.values())if(lease.until()<observedAt-NetworkProtocol.CLOCK_SKEW_MS||lease.until()>observedAt+6000)throw new IllegalArgumentException("Invalid lease lifetime");
        }
    }
    private TeamPackets() {}
    private static void point(DataOutputStream out,Endpoint point)throws IOException {
        out.writeBoolean(point!=null);if(point!=null){text(out,point.dimension());out.writeInt(point.x());out.writeInt(point.y());out.writeInt(point.z());}
    }
    private static Endpoint point(DataInputStream in)throws IOException { return in.readBoolean()?new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt()):null; }
    private static void intent(DataOutputStream out,Intent p)throws IOException {
        uuid(out,p.reporter());text(out,p.reporterName());out.writeByte(p.kind().ordinal());uuid(out,p.target());point(out,p.point());point(out,p.work());out.writeBoolean(p.explosive());out.writeBoolean(p.engaged());out.writeLong(p.observedAt());
    }
    private static Intent intent(DataInputStream in)throws IOException {
        UUID id=uuid(in);String name=text(in,16);int kind=in.readUnsignedByte();if(kind>=Kind.values().length)throw new IOException("Invalid team work");
        return new Intent(id,name,Kind.values()[kind],uuid(in),point(in),point(in),in.readBoolean(),in.readBoolean(),in.readLong());
    }
    static void encode(DataOutputStream out,Packet packet)throws IOException {
        if(packet instanceof Intent p)intent(out,p);
        else if(packet instanceof Plan p){
            people(out,p.members());out.writeByte(p.intents().size());for(Intent intent:p.intents())intent(out,intent);
            out.writeByte(p.leases().size());for(var entry:p.leases().entrySet()){uuid(out,entry.getKey());point(out,entry.getValue().point());out.writeLong(entry.getValue().until());}
        }
    }
    static Packet decode(DataInputStream in,int type,UUID reporter,String name,long time)throws IOException {
        if(type==9){Intent p=intent(in);if(!p.reporter().equals(reporter)||!p.reporterName().equals(name)||p.observedAt()!=time)throw new IOException("Team identity mismatch");return p;}
        Map<UUID,String> members=people(in,64,16);int size=in.readUnsignedByte();if(size>17)throw new IOException("Too many team intents");
        List<Intent> intents=new ArrayList<>();for(int i=0;i<size;i++)intents.add(intent(in));
        size=in.readUnsignedByte();if(size>64)throw new IOException("Too many mining leases");Map<UUID,Lease> leases=new LinkedHashMap<>();
        for(int i=0;i<size;i++){UUID id=uuid(in);if(id==null||leases.put(id,new Lease(point(in),in.readLong()))!=null)throw new IOException("Duplicate mining lease");}
        return new Plan(reporter,name,members,intents,leases,time);
    }
}

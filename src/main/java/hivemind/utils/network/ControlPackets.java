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
import hivemind.utils.network.NetworkProtocol.Message;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Live control data is deliberately excluded from the exploration journal and from history replay. */
public final class ControlPackets {
    public sealed interface Packet extends Message permits OperationsPackets.Snapshot, OperationsPackets.Obstacle, OperationsPackets.Ping, Contact, State, Request, Execute, Policy, Receipt, Defaults, TeamPackets.Intent, TeamPackets.Plan {
        @Override default boolean valid(long now) {
            long ttl=this instanceof OperationsPackets.Snapshot?10000:this instanceof OperationsPackets.Obstacle?600000:this instanceof OperationsPackets.Ping?10000:this instanceof Contact?6000:this instanceof TeamPackets.Plan?4000:this instanceof TeamPackets.Intent?6000:this instanceof State?20000:this instanceof Receipt?120000:60000;
            return observedAt()>=0&&observedAt()<=now+NetworkProtocol.CLOCK_SKEW_MS&&now-observedAt()<=ttl;
        }
    }
    /** Short-lived ESP player detection, never historical proof of a visual sighting or a base. */
    public record Contact(UUID reporter,String reporterName,UUID player,String playerName,Endpoint point,long observedAt) implements Packet {
        public Contact {base(reporter,reporterName);base(player,playerName);Objects.requireNonNull(point);}
        public NetworkProtocol.Sighting hint(){return new NetworkProtocol.Sighting(reporter,reporterName,player,playerName,point,observedAt);}
    }
    public enum Action { COMMAND, SETTING, TRUST }
    public record State(UUID reporter,String reporterName,String status,float health,int food,Endpoint point,
                        Map<UUID,String> trusted,Map<String,String> settings,Map<UUID,String> players,long policyVersion,long observedAt,Map<String,String> update,Map<String,String> session) implements Packet {
        public State(UUID reporter,String reporterName,String status,float health,int food,Endpoint point,Map<UUID,String> trusted,Map<String,String> settings,Map<UUID,String> players,long policyVersion,long observedAt){this(reporter,reporterName,status,health,food,point,trusted,settings,players,policyVersion,observedAt,Map.of(),Map.of());}
        public State {
            base(reporter,reporterName);check(status,512);Objects.requireNonNull(point);
            if(!Float.isFinite(health)||health<0||health>1024||food<0||food>20)throw new IllegalArgumentException("Invalid bot vitals");
            trusted=uuidMap(trusted,128,64);players=uuidMap(players,256,16);settings=stringMap(settings,160);update=stringMap(update,16);session=stringMap(session,32);
        }
    }
    public record Request(UUID reporter,String reporterName,UUID id,UUID target,Action action,String key,String value,long observedAt) implements Packet {
        public Request { base(reporter,reporterName);Objects.requireNonNull(id);Objects.requireNonNull(action);check(key,128);check(value,512); }
    }
    /** Only the authenticated relay is allowed to issue execution or policy packets. */
    public record Execute(UUID reporter,String reporterName,UUID id,UUID requester,UUID target,String command,long observedAt) implements Packet {
        public Execute { base(reporter,reporterName);Objects.requireNonNull(id);Objects.requireNonNull(requester);check(command,220); }
    }
    public record Policy(UUID reporter,String reporterName,UUID id,UUID requester,UUID target,long version,
                         Map<String,String> settings,Map<UUID,Boolean> trust,long observedAt) implements Packet {
        public Policy {
            base(reporter,reporterName);Objects.requireNonNull(id);Objects.requireNonNull(requester);Objects.requireNonNull(target);
            settings=stringMap(settings,160);
            if(version<0||trust.size()>128)throw new IllegalArgumentException("Invalid policy");
            trust.forEach((key,value)->{Objects.requireNonNull(key);Objects.requireNonNull(value);});
            trust=Map.copyOf(trust);
        }
    }
    public enum Stage { ACCEPTED, STARTED, BLOCKED, COMPLETED, CANCELLED, APPLIED, FAILED }
    public record Receipt(UUID reporter,String reporterName,UUID id,UUID requester,boolean success,String message,long observedAt,Stage stage) implements Packet {
        public Receipt(UUID reporter,String name,UUID id,UUID requester,boolean success,String message,long time){this(reporter,name,id,requester,success,message,time,success?Stage.APPLIED:Stage.FAILED);}
        public Receipt { base(reporter,reporterName);Objects.requireNonNull(id);Objects.requireNonNull(requester);Objects.requireNonNull(stage);check(message,512); }
    }
    public record Defaults(UUID reporter,String reporterName,long version,Map<String,String> settings,Map<UUID,Boolean> trust,long observedAt) implements Packet {
        public Defaults {
            base(reporter,reporterName);settings=stringMap(settings,160);
            if(version<0||trust.size()>128)throw new IllegalArgumentException("Invalid defaults");
            trust.forEach((key,value)->{Objects.requireNonNull(key);Objects.requireNonNull(value);});trust=Map.copyOf(trust);
        }
    }
    private ControlPackets() {}
    static void base(UUID id,String name) { Objects.requireNonNull(id);if(name==null||!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid bot identity"); }
    static void check(String value,int length) { if(value==null||value.length()>length||value.chars().anyMatch(ch->ch<32&&ch!='\n'))throw new IllegalArgumentException("Invalid control text"); }
    private static Map<String,String> stringMap(Map<String,String> values,int limit) {
        if(values.size()>limit)throw new IllegalArgumentException("Too many settings");
        values.forEach((key,value)->{check(key,128);check(value,128);});
        return Map.copyOf(values);
    }
    static Map<UUID,String> uuidMap(Map<UUID,String> values,int limit,int length) {
        if(values.size()>limit)throw new IllegalArgumentException("Too many players");
        values.forEach((key,value)->{Objects.requireNonNull(key);check(value,length);});
        return Map.copyOf(values);
    }
    public static int type(Packet p) { return p instanceof OperationsPackets.Snapshot?15:p instanceof OperationsPackets.Obstacle?16:p instanceof OperationsPackets.Ping?17:p instanceof Contact?14:p instanceof State?3:p instanceof Request?4:p instanceof Execute?5:p instanceof Policy?6:p instanceof Receipt?7:p instanceof Defaults?8:p instanceof TeamPackets.Intent?9:10; }
    static void uuid(DataOutputStream out,UUID id)throws IOException { out.writeBoolean(id!=null);if(id!=null){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());} }
    static UUID uuid(DataInputStream in)throws IOException { return in.readBoolean()?new UUID(in.readLong(),in.readLong()):null; }
    static void text(DataOutputStream out,String value)throws IOException { byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeShort(bytes.length);out.write(bytes); }
    static String text(DataInputStream in,int limit)throws IOException {
        int size=in.readUnsignedShort();if(size>limit*4)throw new IOException("Control text too long");
        byte[] bytes=new byte[size];in.readFully(bytes);String value=new String(bytes,StandardCharsets.UTF_8);check(value,limit);return value;
    }
    private static void strings(DataOutputStream out,Map<String,String> map)throws IOException { out.writeShort(map.size());for(var entry:map.entrySet()){text(out,entry.getKey());text(out,entry.getValue());} }
    private static Map<String,String> strings(DataInputStream in)throws IOException {
        int count=in.readUnsignedShort();if(count>160)throw new IOException("Too many settings");
        Map<String,String> map=new LinkedHashMap<>();for(int i=0;i<count;i++){String key=text(in,128);if(map.put(key,text(in,128))!=null)throw new IOException("Duplicate setting");}return map;
    }
    static void people(DataOutputStream out,Map<UUID,String> map)throws IOException { out.writeShort(map.size());for(var entry:map.entrySet()){uuid(out,entry.getKey());text(out,entry.getValue());} }
    static Map<UUID,String> people(DataInputStream in,int limit,int length)throws IOException {
        int count=in.readUnsignedShort();if(count>limit)throw new IOException("Too many players");
        Map<UUID,String> map=new LinkedHashMap<>();for(int i=0;i<count;i++){UUID id=uuid(in);if(id==null||map.put(id,text(in,length))!=null)throw new IOException("Invalid player map");}return map;
    }
    public static void encode(DataOutputStream out,Packet packet)throws IOException {
        encode(out,packet,NetworkProtocol.VERSION);
    }
    public static void encode(DataOutputStream out,Packet packet,int protocol)throws IOException {
        out.writeLong(packet.observedAt());
        if(packet instanceof OperationsPackets.Snapshot||packet instanceof OperationsPackets.Obstacle||packet instanceof OperationsPackets.Ping)OperationsPackets.encode(out,packet,protocol);
        else if(packet instanceof TeamPackets.Intent||packet instanceof TeamPackets.Plan){TeamPackets.encode(out,packet,protocol);}
        else if(packet instanceof Contact p){uuid(out,p.player());text(out,p.playerName());text(out,p.point().dimension());out.writeInt(p.point().x());out.writeInt(p.point().y());out.writeInt(p.point().z());}
        else if(packet instanceof State p){
            text(out,p.status());out.writeFloat(p.health());out.writeByte(p.food());
            text(out,p.point().dimension());out.writeInt(p.point().x());out.writeInt(p.point().y());out.writeInt(p.point().z());
            people(out,p.trusted());strings(out,protocol>=8?p.settings():legacy(p.settings()));people(out,p.players());out.writeLong(p.policyVersion());strings(out,p.update());strings(out,p.session());
        }else if(packet instanceof Request p){uuid(out,p.id());uuid(out,p.target());out.writeByte(p.action().ordinal());text(out,p.key());text(out,p.value());}
        else if(packet instanceof Execute p){uuid(out,p.id());uuid(out,p.requester());uuid(out,p.target());text(out,p.command());}
        else if(packet instanceof Policy p){
            uuid(out,p.id());uuid(out,p.requester());uuid(out,p.target());out.writeLong(p.version());strings(out,protocol>=8?p.settings():legacy(p.settings()));
            out.writeShort(p.trust().size());for(var entry:p.trust().entrySet()){uuid(out,entry.getKey());out.writeBoolean(entry.getValue());}
        }else if(packet instanceof Receipt p){uuid(out,p.id());uuid(out,p.requester());out.writeBoolean(p.success());text(out,p.message());if(protocol>=8)out.writeByte(p.stage().ordinal());}
        else if(packet instanceof Defaults p){
            out.writeLong(p.version());strings(out,protocol>=8?p.settings():legacy(p.settings()));out.writeShort(p.trust().size());
            for(var entry:p.trust().entrySet()){uuid(out,entry.getKey());out.writeBoolean(entry.getValue());}
        }
    }
    public static Packet decode(DataInputStream in,int type,UUID reporter,String name)throws IOException {
        return decode(in,type,reporter,name,NetworkProtocol.VERSION);
    }
    private static Map<String,String> legacy(Map<String,String> values){Map<String,String> result=new LinkedHashMap<>();values.forEach((key,value)->{if(NetworkSettings.legacySupported(key)&&result.size()<128)result.put(key,value);});return result;}
    public static Packet decode(DataInputStream in,int type,UUID reporter,String name,int protocol)throws IOException {
        long time=in.readLong();
        return switch(type){
            case 15,16,17 -> OperationsPackets.decode(in,type,reporter,name,time,protocol);
            case 14 -> new Contact(reporter,name,uuid(in),text(in,16),new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt()),time);
            case 9,10 -> TeamPackets.decode(in,type,reporter,name,time,protocol);
            case 3 -> new State(reporter,name,text(in,512),in.readFloat(),in.readUnsignedByte(),new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt()),people(in,128,64),strings(in),people(in,256,16),in.readLong(),time,strings(in),strings(in));
            case 4 -> {UUID id=uuid(in),target=uuid(in);int action=in.readUnsignedByte();if(action>=Action.values().length)throw new IOException("Unknown control action");yield new Request(reporter,name,id,target,Action.values()[action],text(in,128),text(in,512),time);}
            case 5 -> new Execute(reporter,name,uuid(in),uuid(in),uuid(in),text(in,220),time);
            case 6 -> {
                UUID id=uuid(in),requester=uuid(in),target=uuid(in);long version=in.readLong();Map<String,String> settings=strings(in);
                int count=in.readUnsignedShort();if(count>128)throw new IOException("Too many trust changes");
                Map<UUID,Boolean> trust=new LinkedHashMap<>();for(int i=0;i<count;i++){UUID player=uuid(in);if(player==null||trust.put(player,in.readBoolean())!=null)throw new IOException("Duplicate trust change");}
                yield new Policy(reporter,name,id,requester,target,version,settings,trust,time);
            }
            case 7 -> {UUID id=uuid(in),requester=uuid(in);boolean success=in.readBoolean();String message=text(in,512);int stage=protocol>=8?in.readUnsignedByte():(success?Stage.APPLIED.ordinal():Stage.FAILED.ordinal());if(stage>=Stage.values().length)throw new IOException("Invalid order stage");yield new Receipt(reporter,name,id,requester,success,message,time,Stage.values()[stage]);}
            case 8 -> {
                long version=in.readLong();Map<String,String> settings=strings(in);int count=in.readUnsignedShort();
                if(count>128)throw new IOException("Too many trust defaults");
                Map<UUID,Boolean> trust=new LinkedHashMap<>();
                for(int i=0;i<count;i++){UUID id=uuid(in);if(id==null||trust.put(id,in.readBoolean())!=null)throw new IOException("Invalid trust defaults");}
                yield new Defaults(reporter,name,version,settings,trust,time);
            }
            default -> throw new IOException("Unknown control packet");
        };
    }
}

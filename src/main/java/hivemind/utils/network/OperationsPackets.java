/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.network.ControlPackets.Packet;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.io.*;
import java.util.*;
import hivemind.utils.reliability.RegionRules;
import static hivemind.utils.network.ControlPackets.*;
/** Live activity and transport data; never persisted as observation evidence or replayed as work. */
public final class OperationsPackets {
    public record Snapshot(UUID reporter,String reporterName,Map<String,String> details,List<TaskQueue.Task> tasks,boolean paused,long observedAt,List<RegionRules.Region> regions) implements Packet {
        public Snapshot(UUID reporter,String name,Map<String,String> details,List<TaskQueue.Task> tasks,boolean paused,long time){this(reporter,name,details,tasks,paused,time,List.of());}
        public Snapshot {base(reporter,reporterName);if(details.size()>64||tasks.size()>32||regions.size()>64)throw new IllegalArgumentException("Operations snapshot too large");details.forEach((k,v)->{check(k,40);check(v,256);});details=Map.copyOf(details);tasks=List.copyOf(tasks);regions=List.copyOf(regions);}
    }
    public record Obstacle(UUID reporter,String reporterName,Endpoint point,String reason,long until,long observedAt) implements Packet {
        public Obstacle {base(reporter,reporterName);Objects.requireNonNull(point);check(reason,128);if(until<observedAt||until>observedAt+600000)throw new IllegalArgumentException("Invalid obstacle expiry");}
    }
    public record Ping(UUID reporter,String reporterName,long nonce,boolean reply,long observedAt) implements Packet {public Ping{base(reporter,reporterName);if(nonce<0)throw new IllegalArgumentException("Invalid ping");}}
    private OperationsPackets(){}
    private static void point(DataOutputStream out,Endpoint p)throws IOException{text(out,p.dimension());out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    private static Endpoint point(DataInputStream in)throws IOException{return new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt());}
    private static Map<String,String> legacyDetails(Map<String,String> values){Map<String,String> result=new LinkedHashMap<>();for(String key:List.of("task","blocker","next","flight","recoveries","available","goal","loadout","deathInventory","kit"))if(values.containsKey(key))result.put(key,values.get(key));values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{if(result.size()<40)result.putIfAbsent(e.getKey(),e.getValue());});return result;}
    static void encode(DataOutputStream out,Packet packet,int protocol)throws IOException{
        if(packet instanceof Snapshot p){var details=protocol>=8?p.details():legacyDetails(p.details());out.writeByte(details.size());for(var e:details.entrySet()){text(out,e.getKey());text(out,e.getValue());}out.writeBoolean(p.paused());out.writeByte(p.tasks().size());for(var t:p.tasks()){out.writeLong(t.id());text(out,t.kind());point(out,t.point());out.writeInt(t.radius());}if(protocol>=8){out.writeByte(p.regions().size());for(var r:p.regions()){text(out,r.name());text(out,r.kind().name());point(out,r.center());out.writeInt(r.radius());}}}
        else if(packet instanceof Obstacle p){point(out,p.point());text(out,p.reason());out.writeLong(p.until());}
        else if(packet instanceof Ping p){out.writeLong(p.nonce());out.writeBoolean(p.reply());}
    }
    static Packet decode(DataInputStream in,int type,UUID reporter,String name,long time,int protocol)throws IOException{
        if(type==16)return new Obstacle(reporter,name,point(in),text(in,128),in.readLong(),time);
        if(type==17)return new Ping(reporter,name,in.readLong(),in.readBoolean(),time);
        int size=in.readUnsignedByte();if(size>64)throw new IOException("Too many operation details");Map<String,String> details=new LinkedHashMap<>();for(int i=0;i<size;i++){String key=text(in,40);if(details.put(key,text(in,256))!=null)throw new IOException("Duplicate operation detail");}boolean paused=in.readBoolean();size=in.readUnsignedByte();if(size>32)throw new IOException("Too many map tasks");List<TaskQueue.Task> tasks=new ArrayList<>();for(int i=0;i<size;i++)tasks.add(new TaskQueue.Task(in.readLong(),text(in,16),point(in),in.readInt()));size=protocol>=8?in.readUnsignedByte():0;if(size>64)throw new IOException("Too many behavior regions");List<RegionRules.Region> regions=new ArrayList<>();for(int i=0;i<size;i++)regions.add(new RegionRules.Region(text(in,32),RegionRules.Kind.valueOf(text(in,16)),point(in),in.readInt()));return new Snapshot(reporter,name,details,tasks,paused,time,regions);
    }
    public static String json(Collection<Snapshot> snapshots){StringJoiner out=new StringJoiner(",","{","}");for(var p:snapshots){StringJoiner tasks=new StringJoiner(",","[","]");for(var t:p.tasks()){var e=t.point();tasks.add("{\"id\":"+t.id()+",\"kind\":"+NetworkAtlas.quote(t.kind())+",\"d\":"+NetworkAtlas.quote(e.dimension())+",\"x\":"+e.x()+",\"y\":"+e.y()+",\"z\":"+e.z()+",\"radius\":"+t.radius()+"}");}StringJoiner regions=new StringJoiner(",","[","]");for(var r:p.regions()){var e=r.center();regions.add("{\"name\":"+NetworkAtlas.quote(r.name())+",\"kind\":"+NetworkAtlas.quote(r.kind().name().toLowerCase(Locale.ROOT))+",\"d\":"+NetworkAtlas.quote(e.dimension())+",\"x\":"+e.x()+",\"y\":"+e.y()+",\"z\":"+e.z()+",\"radius\":"+r.radius()+"}");}out.add(NetworkAtlas.quote(p.reporter().toString())+":{\"details\":"+ControlView.strings(p.details())+",\"paused\":"+p.paused()+",\"tasks\":"+tasks+",\"regions\":"+regions+",\"t\":"+p.observedAt()+"}");}return out.toString();}
}

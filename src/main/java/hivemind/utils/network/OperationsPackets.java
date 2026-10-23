/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.network.ControlPackets.Packet;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.io.*;
import java.util.*;
import static hivemind.utils.network.ControlPackets.*;
/** Live activity and transport data; never persisted as observation evidence or replayed as work. */
public final class OperationsPackets {
    public record Snapshot(UUID reporter,String reporterName,Map<String,String> details,List<TaskQueue.Task> tasks,boolean paused,long observedAt) implements Packet {
        public Snapshot {base(reporter,reporterName);if(details.size()>64||tasks.size()>32)throw new IllegalArgumentException("Operations snapshot too large");details.forEach((k,v)->{check(k,40);check(v,256);});details=Map.copyOf(details);tasks=List.copyOf(tasks);}
    }
    public record Obstacle(UUID reporter,String reporterName,Endpoint point,String reason,long until,long observedAt) implements Packet {
        public Obstacle {base(reporter,reporterName);Objects.requireNonNull(point);check(reason,128);if(until<observedAt||until>observedAt+600000)throw new IllegalArgumentException("Invalid obstacle expiry");}
    }
    public record Ping(UUID reporter,String reporterName,long nonce,boolean reply,long observedAt) implements Packet {public Ping{base(reporter,reporterName);if(nonce<0)throw new IllegalArgumentException("Invalid ping");}}
    private OperationsPackets(){}
    private static void point(DataOutputStream out,Endpoint p)throws IOException{text(out,p.dimension());out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());}
    private static Endpoint point(DataInputStream in)throws IOException{return new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt());}
    static void encode(DataOutputStream out,Packet packet)throws IOException{
        if(packet instanceof Snapshot p){out.writeByte(p.details().size());for(var e:p.details().entrySet()){text(out,e.getKey());text(out,e.getValue());}out.writeBoolean(p.paused());out.writeByte(p.tasks().size());for(var t:p.tasks()){out.writeLong(t.id());text(out,t.kind());point(out,t.point());out.writeInt(t.radius());}}
        else if(packet instanceof Obstacle p){point(out,p.point());text(out,p.reason());out.writeLong(p.until());}
        else if(packet instanceof Ping p){out.writeLong(p.nonce());out.writeBoolean(p.reply());}
    }
    static Packet decode(DataInputStream in,int type,UUID reporter,String name,long time)throws IOException{
        if(type==16)return new Obstacle(reporter,name,point(in),text(in,128),in.readLong(),time);
        if(type==17)return new Ping(reporter,name,in.readLong(),in.readBoolean(),time);
        int size=in.readUnsignedByte();if(size>64)throw new IOException("Too many operation details");Map<String,String> details=new LinkedHashMap<>();for(int i=0;i<size;i++){String key=text(in,40);if(details.put(key,text(in,256))!=null)throw new IOException("Duplicate operation detail");}boolean paused=in.readBoolean();size=in.readUnsignedByte();if(size>32)throw new IOException("Too many map tasks");List<TaskQueue.Task> tasks=new ArrayList<>();for(int i=0;i<size;i++)tasks.add(new TaskQueue.Task(in.readLong(),text(in,16),point(in),in.readInt()));return new Snapshot(reporter,name,details,tasks,paused,time);
    }
    public static String json(Collection<Snapshot> snapshots){StringJoiner out=new StringJoiner(",","{","}");for(var p:snapshots){StringJoiner tasks=new StringJoiner(",","[","]");for(var t:p.tasks()){var e=t.point();tasks.add("{\"id\":"+t.id()+",\"kind\":"+NetworkAtlas.quote(t.kind())+",\"d\":"+NetworkAtlas.quote(e.dimension())+",\"x\":"+e.x()+",\"y\":"+e.y()+",\"z\":"+e.z()+",\"radius\":"+t.radius()+"}");}out.add(NetworkAtlas.quote(p.reporter().toString())+":{\"details\":"+ControlView.strings(p.details())+",\"paused\":"+p.paused()+",\"tasks\":"+tasks+",\"t\":"+p.observedAt()+"}");}return out.toString();}
}

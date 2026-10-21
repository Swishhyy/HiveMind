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

import baritone.utils.bot.AtomicProperties;
import baritone.utils.network.NetworkProtocol.Message;
import baritone.utils.network.TerrainPackets.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static baritone.utils.network.NetworkAtlas.quote;

/** Bounded terrain/site history, isolated per Minecraft server just like the observation atlas. */
public final class TerrainStore {
    private record Key(String dimension,int x,int z){}
    private final Map<Key,Tile> tiles=new LinkedHashMap<>();
    private final Map<Key,Site> sites=new LinkedHashMap<>();
    private record MarkerKey(UUID reporter,UUID id){}
    private final Map<MarkerKey,Marker> markers=new LinkedHashMap<>();
    private final Path file;
    private long revision,savedRevision,lastPrune;
    public TerrainStore(){file=null;}
    public TerrainStore(Path root){
        file=root.resolve("bot-network-terrain.properties");
        try{
            if(Files.exists(file)&&Files.size(file)>20_000_000)throw new IOException("Terrain history exceeds 20 MB");
            Properties p=AtomicProperties.read(file);int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>5120)throw new IOException("Too many terrain records");
            long now=System.currentTimeMillis();for(int i=0;i<count;i++){String value=p.getProperty("entry."+i,"");if(value.length()>6000)throw new IOException("Oversized terrain record");Message message=NetworkProtocol.decode(Base64.getDecoder().decode(value));if(!(message instanceof Tile||message instanceof Site||message instanceof Marker))throw new IOException("Unexpected terrain record");accept(message,now);}
            savedRevision=revision;
        }catch(IOException|IllegalArgumentException error){throw new IllegalStateException("Invalid bot-network-terrain.properties",error);}
    }
    public synchronized boolean accept(Message message,long now){
        prune(now);if(message==null||!message.valid(now))return false;
        if(message instanceof Tile tile){
            Key key=new Key(tile.dimension(),tile.x(),tile.z());Tile old=tiles.get(key);
            if(old!=null&&(old.observedAt()>=tile.observedAt()||old.colors().equals(tile.colors())&&old.heights().equals(tile.heights())&&tile.observedAt()-old.observedAt()<60000))return false;
            tiles.put(key,tile);if(tiles.size()>4096)tiles.remove(tiles.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().observedAt())).orElseThrow().getKey());
        }else if(message instanceof Site site){
            Key key=new Key(site.point().dimension(),Math.floorDiv(site.point().x(),16),Math.floorDiv(site.point().z(),16));Site old=sites.get(key);
            if(old!=null&&(old.confirmed()&&!site.confirmed()||old.observedAt()>=site.observedAt()||old.label().equals(site.label())&&old.evidence().equals(site.evidence())&&old.confirmed()==site.confirmed()&&site.observedAt()-old.observedAt()<60000))return false;
            sites.put(key,site);if(sites.size()>512)sites.remove(sites.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().observedAt())).orElseThrow().getKey());
        }else if(message instanceof Marker marker){
            MarkerKey key=new MarkerKey(marker.reporter(),marker.id());Marker old=markers.get(key);
            if(old!=null&&(old.observedAt()>marker.observedAt()||old.observedAt()==marker.observedAt()&&(old.deleted()||!marker.deleted())))return false;
            markers.put(key,marker);if(markers.size()>512)markers.remove(markers.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().observedAt())).orElseThrow().getKey());
        }else return false;
        revision++;return true;
    }
    private void prune(long now){if(now-lastPrune<10000)return;lastPrune=now;int count=tiles.size()+sites.size()+markers.size();tiles.values().removeIf(p->!p.valid(now));sites.values().removeIf(p->!p.valid(now));markers.values().removeIf(p->!p.valid(now));if(count!=tiles.size()+sites.size()+markers.size())revision++;}
    public synchronized Tile tile(String dimension,int x,int z){return tiles.get(new Key(dimension,x,z));}
    public synchronized List<Marker> markers(long now){prune(now);return markers.values().stream().filter(p->!p.deleted()).sorted(Comparator.comparingLong(Marker::observedAt).reversed()).toList();}
    public synchronized Marker marker(UUID reporter,UUID id){return markers.get(new MarkerKey(reporter,id));}
    public synchronized List<Message> snapshot(long now){prune(now);List<Message> result=new ArrayList<>(tiles.values());result.addAll(sites.values());result.addAll(markers.values());result.sort(Comparator.comparingLong(Message::observedAt).reversed());return result;}
    public synchronized boolean dirty(){return revision!=savedRevision;}
    public synchronized void clear(){tiles.clear();sites.clear();markers.clear();revision++;}
    public void save(){
        if(file==null)return;List<Message> snapshot;long version;synchronized(this){if(!dirty())return;snapshot=snapshot(System.currentTimeMillis());version=revision;}
        Properties p=new Properties();p.setProperty("count",""+snapshot.size());
        try{for(int i=0;i<snapshot.size();i++)p.setProperty("entry."+i,Base64.getEncoder().encodeToString(NetworkProtocol.encode(snapshot.get(i))));}catch(IOException error){throw new IllegalStateException("Cannot encode terrain history",error);}
        AtomicProperties.write(file,p);synchronized(this){savedRevision=version;}
    }
    public synchronized String index(long now){
        prune(now);StringJoiner values=new StringJoiner(",");for(Tile tile:tiles.values())values.add("{\"d\":"+quote(tile.dimension())+",\"x\":"+tile.x()+",\"z\":"+tile.z()+",\"t\":"+tile.observedAt()+"}");
        StringBuilder out=new StringBuilder("{\"revision\":").append(revision).append(",\"tiles\":[").append(values).append("],\"sites\":[");values=new StringJoiner(",");
        for(Site site:sites.values())values.add("{\"d\":"+quote(site.point().dimension())+",\"x\":"+site.point().x()+",\"y\":"+site.point().y()+",\"z\":"+site.point().z()+",\"name\":"+quote(site.label())+",\"evidence\":"+quote(site.evidence())+",\"confirmed\":"+site.confirmed()+",\"source\":"+quote(site.reporterName())+",\"t\":"+site.observedAt()+"}");
        out.append(values).append("],\"markers\":[");values=new StringJoiner(",");
        for(Marker p:markers(now))values.add("{\"id\":"+quote(p.id().toString())+",\"reporter\":"+quote(p.reporter().toString())+",\"kind\":"+quote(p.kind())+",\"d\":"+quote(p.point().dimension())+",\"x\":"+p.point().x()+",\"y\":"+p.point().y()+",\"z\":"+p.point().z()+",\"name\":"+quote(p.label())+",\"detail\":"+quote(p.detail())+",\"source\":"+quote(p.reporterName())+",\"seen\":"+p.seenAt()+",\"t\":"+p.observedAt()+"}");
        return out.append(values).append("]}").toString();
    }
    public static String json(Tile tile){return "{\"d\":"+quote(tile.dimension())+",\"x\":"+tile.x()+",\"z\":"+tile.z()+",\"t\":"+tile.observedAt()+",\"rgb\":"+quote(tile.colors())+",\"h\":"+quote(tile.heights())+"}";}
}

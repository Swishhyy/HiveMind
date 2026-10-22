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
import java.nio.file.Path;
import java.util.*;

/** World-specific command state; none of these endpoints can leak to another server/save. */
public final class BotLocations {
    public record Death(Endpoint point, long time, Map<String,Integer> items, int attempts) {
        public Death { items = Map.copyOf(items); if (time < 0 || attempts < 0 || items.size() > 128) throw new IllegalArgumentException("Invalid recovery record"); }
    }
    private final Path file;
    private Properties values;
    public BotLocations(Path root) { file = root.resolve("bot-locations.properties"); values = AtomicProperties.read(file); }
    public synchronized Endpoint station(String name) { return endpoint("station." + name); }
    public synchronized void setStation(String name, Endpoint point) {
        if (!Set.of("repair","crafting","furnace").contains(name)) throw new IllegalArgumentException("Unknown station");
        Properties p = copy(); put(p,"station."+name,point); commit(p);
    }
    public synchronized Endpoint corner(String dimension,int index) { return endpoint("corner."+dimension+"."+index); }
    public synchronized void setCorner(String dimension,int index,Endpoint point) { Properties p=copy();put(p,"corner."+dimension+"."+index,point);commit(p); }
    public synchronized boolean zonesEnabled() { return Boolean.parseBoolean(values.getProperty("zones.enabled","false")); }
    public synchronized void enableZones(boolean enabled) { Properties p = copy(); p.setProperty("zones.enabled",Boolean.toString(enabled)); commit(p); }
    public synchronized MiningZone zone(String dimension) {
        String prefix="zone."+dimension+".";
        if (!values.containsKey(prefix+"minX")) return null;
        try { return new MiningZone(dimension,number(prefix+"minX"),number(prefix+"minY"),number(prefix+"minZ"),number(prefix+"maxX"),number(prefix+"maxY"),number(prefix+"maxZ")); }
        catch (RuntimeException e) { throw new IllegalStateException("Invalid mining zone; correct or clear it before digging",e); }
    }
    public synchronized void setZone(String dimension, MiningZone zone) {
        Properties p=copy(); String prefix="zone."+dimension+".";
        p.stringPropertyNames().stream().filter(key->key.startsWith(prefix)).toList().forEach(p::remove);
        if(zone!=null){
            p.setProperty(prefix+"minX",""+zone.minX());p.setProperty(prefix+"minY",""+zone.minY());p.setProperty(prefix+"minZ",""+zone.minZ());
            p.setProperty(prefix+"maxX",""+zone.maxX());p.setProperty(prefix+"maxY",""+zone.maxY());p.setProperty(prefix+"maxZ",""+zone.maxZ());
            p.setProperty("zones.enabled","true");
        } else if(p.stringPropertyNames().stream().noneMatch(key->key.startsWith("zone.")&&key.endsWith(".minX"))) p.setProperty("zones.enabled","false");
        commit(p);
    }
    public synchronized Death death() {
        Endpoint point=endpoint("death"); if(point==null)return null;
        Map<String,Integer> items=new LinkedHashMap<>();
        for(String key:values.stringPropertyNames())if(key.startsWith("death.item.")){
            String id=key.substring(11);int amount=number(key);if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||amount<1||amount>1000000)throw new IllegalStateException("Invalid death inventory");items.put(id,amount);
        }
        return new Death(point,Long.parseLong(values.getProperty("death.time","0")),items,Integer.parseInt(values.getProperty("death.attempts","0")));
    }
    public synchronized void setDeath(Death death) {
        Properties p=copy();p.stringPropertyNames().stream().filter(key->key.startsWith("death.")).toList().forEach(p::remove);
        if(death!=null){put(p,"death",death.point());p.setProperty("death.time",""+death.time());p.setProperty("death.attempts",""+death.attempts());death.items().forEach((id,n)->p.setProperty("death.item."+id,""+n));}commit(p);
    }
    private int number(String key){return Integer.parseInt(values.getProperty(key));}
    private Endpoint endpoint(String prefix){
        if(!values.containsKey(prefix+".dimension"))return null;
        try{return new Endpoint(values.getProperty(prefix+".dimension"),number(prefix+".x"),number(prefix+".y"),number(prefix+".z"));}
        catch(RuntimeException e){throw new IllegalStateException("Invalid "+prefix+" endpoint",e);}
    }
    private static void put(Properties p,String prefix,Endpoint point){
        for(String key:List.of("dimension","x","y","z"))p.remove(prefix+"."+key);
        if(point!=null){p.setProperty(prefix+".dimension",point.dimension());p.setProperty(prefix+".x",""+point.x());p.setProperty(prefix+".y",""+point.y());p.setProperty(prefix+".z",""+point.z());}
    }
    private Properties copy(){Properties p=new Properties();p.putAll(values);return p;}
    private void commit(Properties p){AtomicProperties.write(file,p);values=p;}
}

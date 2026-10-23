/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Last server-confirmed stock per storage location and Minecraft account. It is a hint, never a withdrawal guarantee. */
public final class StorageStockLedger {
    public record Observation(Endpoint point, boolean privateStock, long seen, Map<String,Integer> items) {
        public Observation { Objects.requireNonNull(point); items=Map.copyOf(items); }
    }
    private final Path file;
    private final Map<Endpoint,Observation> known=new LinkedHashMap<>();
    public StorageStockLedger(Path serverRoot, UUID account) {
        file=serverRoot.resolve("storage-stock-"+account+".properties");
        Properties saved=AtomicProperties.read(file);
        int count=Math.min(64,Math.max(0,parse(saved.getProperty("count"),0)));
        for(int i=0;i<count;i++)try{
            String prefix="entry."+i+".";
            Endpoint point=new Endpoint(saved.getProperty(prefix+"dimension"),parse(saved.getProperty(prefix+"x"),0),parse(saved.getProperty(prefix+"y"),0),parse(saved.getProperty(prefix+"z"),0));
            long seen=Long.parseLong(saved.getProperty(prefix+"seen"));
            if(seen<0||seen>System.currentTimeMillis()+60000)continue;
            Map<String,Integer> items=new LinkedHashMap<>();
            for(String pair:saved.getProperty(prefix+"items","").split(";"))if(!pair.isBlank()){
                String[] parts=pair.split("=",2);if(parts.length==2&&parts[0].matches("[a-z0-9_.:/-]{1,128}")){
                    int amount=parse(parts[1],0);if(amount>0&&amount<=16_000_000&&items.size()<128)items.put(parts[0],amount);
                }
            }
            known.put(point,new Observation(point,Boolean.parseBoolean(saved.getProperty(prefix+"private","false")),seen,items));
        }catch(RuntimeException ignored){/* A malformed historical entry cannot invent stock. */}
    }
    private static int parse(String value,int fallback){try{return Integer.parseInt(value);}catch(RuntimeException ignored){return fallback;}}
    public synchronized void observe(Endpoint point,boolean privateStock,Map<String,Integer> items,long now){
        if(now<0||items.size()>128)throw new IllegalArgumentException("Invalid stock observation");
        Map<String,Integer> counts=new TreeMap<>();
        items.forEach((id,n)->{if(!id.matches("[a-z0-9_.:/-]{1,128}")||n==null||n<1||n>16_000_000)throw new IllegalArgumentException("Invalid stock item");counts.put(id,n);});
        Observation before=known.get(point);
        if(before!=null&&before.privateStock()==privateStock&&before.items().equals(counts)&&now-before.seen()<30000)return;
        known.put(point,new Observation(point,privateStock,now,counts));
        if(known.size()>64){Endpoint oldest=known.values().stream().min(Comparator.comparingLong(Observation::seen)).orElseThrow().point();known.remove(oldest);}
        save();
    }
    public synchronized List<Observation> recent(){return known.values().stream().sorted(Comparator.comparingLong(Observation::seen).reversed()).limit(8).toList();}
    private void save(){
        Properties out=new Properties();out.setProperty("count",""+known.size());int i=0;
        for(Observation entry:known.values()){
            String prefix="entry."+(i++)+".";Endpoint p=entry.point();out.setProperty(prefix+"dimension",p.dimension());out.setProperty(prefix+"x",""+p.x());out.setProperty(prefix+"y",""+p.y());out.setProperty(prefix+"z",""+p.z());out.setProperty(prefix+"private",""+entry.privateStock());out.setProperty(prefix+"seen",""+entry.seen());
            StringJoiner itemList=new StringJoiner(";");entry.items().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->itemList.add(e.getKey()+"="+e.getValue()));out.setProperty(prefix+"items",itemList.toString());
        }
        AtomicProperties.write(file,out);
    }
}

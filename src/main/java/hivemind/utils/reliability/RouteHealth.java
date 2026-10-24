/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Bounded, expiring experience for real storage, portals, launch sites and landing sites. */
public final class RouteHealth {
    public record Entry(String kind,Endpoint point,int failures,int successes,long seen,long avoidUntil,String reason) {}
    private final Path file;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    public RouteHealth(Path root){file=root.resolve("route-health.properties");Properties p=AtomicProperties.read(file);int count=Math.min(256,Integer.parseInt(p.getProperty("count","0")));for(int i=0;i<count;i++)try{String k="entry."+i+".";Endpoint point=new Endpoint(p.getProperty(k+"dimension"),Integer.parseInt(p.getProperty(k+"x")),Integer.parseInt(p.getProperty(k+"y")),Integer.parseInt(p.getProperty(k+"z")));Entry e=new Entry(p.getProperty(k+"kind"),point,Integer.parseInt(p.getProperty(k+"failures","0")),Integer.parseInt(p.getProperty(k+"successes","0")),Long.parseLong(p.getProperty(k+"seen")),Long.parseLong(p.getProperty(k+"avoid","0")),p.getProperty(k+"reason",""));entries.put(key(e.kind(),point),e);}catch(RuntimeException ignored){/* Invalid history cannot authorize an action. */}}
    private static String key(String kind,Endpoint p){return kind+":"+p.dimension()+":"+p.x()+":"+p.y()+":"+p.z();}
    public Entry entry(String kind,Endpoint point){return entries.get(key(kind,point));}
    public boolean available(String kind,Endpoint point,long now){Entry e=entry(kind,point);return e==null||now>=e.avoidUntil();}
    public double penalty(String kind,Endpoint point,long now){Entry e=entry(kind,point);if(e==null||now-e.seen()>3600000)return 0;return now<e.avoidUntil()?100000:Math.max(0,e.failures()*128-e.successes()*16);}
    public void failed(String kind,Endpoint point,String reason,long now){Entry old=entry(kind,point);int failures=old==null?1:Math.min(16,old.failures()+1);put(new Entry(kind,point,failures,old==null?0:old.successes(),now,now+Math.min(600000,(kind.equals("shulker")?120000L:15000L)<<Math.min(6,failures-1)),reason.substring(0,Math.min(180,reason.length()))));}
    public void success(String kind,Endpoint point,long now){Entry old=entry(kind,point);if(old!=null&&old.failures()==0&&now-old.seen()<60000)return;put(new Entry(kind,point,0,old==null?1:Math.min(32,old.successes()+1),now,0,"Confirmed successful use"));}
    public List<Entry> recent(String kind,long now){return entries.values().stream().filter(e->e.kind().equals(kind)&&now-e.seen()<86400000).sorted(Comparator.comparingLong(Entry::seen).reversed()).toList();}
    private void put(Entry e){entries.put(key(e.kind(),e.point()),e);while(entries.size()>256){String oldest=entries.entrySet().stream().min(Comparator.comparingLong(x->x.getValue().seen())).orElseThrow().getKey();entries.remove(oldest);}save();}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("count",""+entries.size());int i=0;for(Entry e:entries.values()){String k="entry."+i+++".";p.setProperty(k+"kind",e.kind());p.setProperty(k+"dimension",e.point().dimension());p.setProperty(k+"x",""+e.point().x());p.setProperty(k+"y",""+e.point().y());p.setProperty(k+"z",""+e.point().z());p.setProperty(k+"failures",""+e.failures());p.setProperty(k+"successes",""+e.successes());p.setProperty(k+"seen",""+e.seen());p.setProperty(k+"avoid",""+e.avoidUntil());p.setProperty(k+"reason",e.reason());}AtomicProperties.write(file,p);}
}

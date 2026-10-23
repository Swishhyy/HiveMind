/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Explicit regions supplement observed construction; block material alone never proves player ownership. */
public final class RegionRules {
    public enum Kind { PROTECT, DANGER, EXPLORE, BUILD, STORAGE }
    public record Region(String name,Kind kind,Endpoint center,int radius){
        public Region {if(name==null||!name.matches("[A-Za-z0-9_-]{1,32}")||kind==null||center==null||radius<4||radius>2048)throw new IllegalArgumentException("Use a simple name and radius 4–2048");}
        public boolean contains(String dimension,int x,int z){return center.dimension().equals(dimension)&&Math.abs((long)x-center.x())<=radius&&Math.abs((long)z-center.z())<=radius;}
    }
    private final Path file;
    private List<Region> regions;
    public RegionRules(Path root){file=root.resolve("behavior-regions.properties");Properties p=AtomicProperties.read(file);int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>64)throw new IllegalStateException("Too many saved regions");List<Region> saved=new ArrayList<>();for(int i=0;i<count;i++){String k="region."+i+".";saved.add(new Region(p.getProperty(k+"name"),Kind.valueOf(p.getProperty(k+"kind")),new Endpoint(p.getProperty(k+"dimension"),Integer.parseInt(p.getProperty(k+"x")),Integer.parseInt(p.getProperty(k+"y")),Integer.parseInt(p.getProperty(k+"z"))),Integer.parseInt(p.getProperty(k+"radius"))));}regions=List.copyOf(saved);}
    public List<Region> regions(){return regions;}
    public void put(Region region){List<Region> next=new ArrayList<>(regions);next.removeIf(r->r.name().equalsIgnoreCase(region.name()));if(next.size()>=64)throw new IllegalStateException("Region limit is 64");next.add(region);save(next);}
    public void remove(String name){List<Region> next=new ArrayList<>(regions);if(!next.removeIf(r->r.name().equalsIgnoreCase(name)))throw new IllegalArgumentException("Unknown region");save(next);}
    private void save(List<Region> next){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("count",""+next.size());for(int i=0;i<next.size();i++){var r=next.get(i);String k="region."+i+".";p.setProperty(k+"name",r.name());p.setProperty(k+"kind",r.kind().name());p.setProperty(k+"dimension",r.center().dimension());p.setProperty(k+"x",""+r.center().x());p.setProperty(k+"y",""+r.center().y());p.setProperty(k+"z",""+r.center().z());p.setProperty(k+"radius",""+r.radius());}AtomicProperties.write(file,p);regions=List.copyOf(next);}
    public static boolean protectedAt(List<Region> regions,String dimension,int x,int z){return regions.stream().anyMatch(r->r.kind()==Kind.PROTECT&&r.contains(dimension,x,z));}
    public static double penalty(List<Region> regions,String dimension,int x,int z){return regions.stream().filter(r->r.kind()==Kind.DANGER&&r.contains(dimension,x,z)).mapToDouble(r->5000).sum();}
}

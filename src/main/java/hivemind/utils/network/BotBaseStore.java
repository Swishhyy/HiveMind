/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;
/** One explicit resupply base per server/save and client instance. Existing storage is not deleted. */
public final class BotBaseStore {
    public record Base(Endpoint point,int radius){public Base{Objects.requireNonNull(point);if(radius<4||radius>32)throw new IllegalArgumentException("Base radius must be 4–32 blocks");}public boolean contains(Endpoint other){return point.dimension().equals(other.dimension())&&Math.abs((long)point.x()-other.x())<=radius&&Math.abs((long)point.y()-other.y())<=radius&&Math.abs((long)point.z()-other.z())<=radius;}}
    private final Path file;
    public BotBaseStore(Path root){file=root.resolve("bot-base.properties");}
    public Base get(){Properties p=AtomicProperties.read(file);if(!p.containsKey("dimension"))return null;return new Base(new Endpoint(p.getProperty("dimension"),Integer.parseInt(p.getProperty("x")),Integer.parseInt(p.getProperty("y")),Integer.parseInt(p.getProperty("z"))),Integer.parseInt(p.getProperty("radius")));}
    public void set(Base base){Properties p=AtomicProperties.read(file);for(String k:List.of("dimension","x","y","z","radius"))p.remove(k);if(base!=null)put(p,"",base);AtomicProperties.write(file,p);}
    private static void put(Properties p,String prefix,Base base){var e=base.point();p.setProperty(prefix+"dimension",e.dimension());p.setProperty(prefix+"x",""+e.x());p.setProperty(prefix+"y",""+e.y());p.setProperty(prefix+"z",""+e.z());p.setProperty(prefix+"radius",""+base.radius());}
    public Map<String,Base> all(){Properties p=AtomicProperties.read(file);Map<String,Base> result=new LinkedHashMap<>();Base primary=get();if(primary!=null)result.put("default",primary);for(String k:p.stringPropertyNames())if(k.startsWith("base.")&&k.endsWith(".dimension")){String prefix=k.substring(0,k.length()-9),name=prefix.substring(5,prefix.length()-1);result.put(name,new Base(new Endpoint(p.getProperty(k),Integer.parseInt(p.getProperty(prefix+"x")),Integer.parseInt(p.getProperty(prefix+"y")),Integer.parseInt(p.getProperty(prefix+"z"))),Integer.parseInt(p.getProperty(prefix+"radius"))));}return Map.copyOf(result);}
    public void named(String name,Base base){if(!name.matches("[A-Za-z0-9_-]{1,32}")||name.equals("default"))throw new IllegalArgumentException("Use a simple base name other than default");Properties p=AtomicProperties.read(file);String prefix="base."+name+".";p.stringPropertyNames().stream().filter(k->k.startsWith(prefix)).toList().forEach(p::remove);if(base!=null){if(all().size()>=16&&!all().containsKey(name))throw new IllegalStateException("At most 16 bases");put(p,prefix,base);}AtomicProperties.write(file,p);}
    public Base best(Endpoint here,java.util.function.ToDoubleFunction<Base> penalty){return all().values().stream().min(Comparator.comparingDouble(base->(base.point().dimension().equals(here.dimension())?Math.hypot((double)base.point().x()-here.x(),(double)base.point().z()-here.z()):8192)+penalty.applyAsDouble(base))).orElse(null);}
}

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
    public void set(Base base){Properties p=new Properties();if(base!=null){var e=base.point();p.setProperty("dimension",e.dimension());p.setProperty("x",""+e.x());p.setProperty("y",""+e.y());p.setProperty("z",""+e.z());p.setProperty("radius",""+base.radius());}AtomicProperties.write(file,p);}
}

/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.bot;

import baritone.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.Properties;

/** One outstanding portable box per server; cancellation never erases its location. */
public final class PortableBoxStore {
    public record Box(Endpoint point,String item,boolean confirmed){
        public Box{if(point==null||item==null||!item.matches("minecraft:(?:[a-z_]+_)?shulker_box"))throw new IllegalArgumentException("Invalid portable box");}
    }
    private final Path file;
    public PortableBoxStore(Path root){file=root.resolve("bot-portable-box.properties");}
    public Box box(){
        Properties p=AtomicProperties.read(file);if(!p.containsKey("item"))return null;
        try{return new Box(new Endpoint(p.getProperty("dimension"),Integer.parseInt(p.getProperty("x")),Integer.parseInt(p.getProperty("y")),Integer.parseInt(p.getProperty("z"))),p.getProperty("item"),Boolean.parseBoolean(p.getProperty("confirmed","false")));}
        catch(IllegalArgumentException e){throw new IllegalStateException("Invalid saved shulker recovery point",e);}
    }
    public void save(Box box){
        Properties p=new Properties();
        if(box!=null){p.setProperty("item",box.item());p.setProperty("dimension",box.point().dimension());p.setProperty("x",""+box.point().x());p.setProperty("y",""+box.point().y());p.setProperty("z",""+box.point().z());p.setProperty("confirmed",""+box.confirmed());}
        AtomicProperties.write(file,p);
    }
}

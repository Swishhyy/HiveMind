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

import java.nio.file.Path;
import java.util.*;

/** A durable named order is distinct from a live movement/combat process. */
public final class TrackingOrderStore {
    public record Order(String name, UUID target, boolean hunt, String style, UUID owner, long createdAt) {
        public Order {
            if(name==null||!name.matches("[A-Za-z0-9_]{1,16}")||!Set.of("auto","melee","crystal","anchor").contains(style)||createdAt<0)
                throw new IllegalArgumentException("Invalid tracking order");
        }
        public Order resolved(UUID id,String canonical){return new Order(canonical,id,hunt,style,owner,createdAt);}
    }
    private final Path file;
    private Order order;
    private boolean paused;
    public TrackingOrderStore(Path root){
        file=root.resolve("bot-tracking-order.properties");Properties p=AtomicProperties.read(file);
        try{
            paused=Boolean.parseBoolean(p.getProperty("paused","false"));
            if(p.containsKey("name"))order=new Order(p.getProperty("name"),id(p.getProperty("target")),Boolean.parseBoolean(p.getProperty("hunt")),p.getProperty("style"),id(p.getProperty("owner")),Long.parseLong(p.getProperty("created")));
        }catch(RuntimeException e){throw new IllegalStateException("Invalid bot-tracking-order.properties",e);}
    }
    private static UUID id(String value){return value==null||value.isEmpty()?null:UUID.fromString(value);}
    public Order order(){return order;}
    public boolean paused(){return paused;}
    public void set(Order next){save(next,paused);order=next;}
    public void pause(boolean next){save(order,next);paused=next;}
    public void clear(){save(null,false);order=null;paused=false;}
    private void save(Order next,boolean held){
        Properties p=new Properties();p.setProperty("paused",Boolean.toString(held));
        if(next!=null){p.setProperty("name",next.name());p.setProperty("hunt",""+next.hunt());p.setProperty("style",next.style());p.setProperty("created",""+next.createdAt());if(next.target()!=null)p.setProperty("target",next.target().toString());if(next.owner()!=null)p.setProperty("owner",next.owner().toString());}
        AtomicProperties.write(file,p);
    }
}

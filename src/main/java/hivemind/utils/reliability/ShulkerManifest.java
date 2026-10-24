/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;

/** Account-private catalog of carried boxes and actual inspections, including explicitly unknown contents. */
public final class ShulkerManifest {
    public record Box(int slot,String item,String signature,boolean known,Map<String,Integer> contents){
        public Box {if(slot<0||slot>=36||item==null||signature==null||contents.size()>64)throw new IllegalArgumentException("Invalid carried box");contents=Map.copyOf(contents);}
    }
    private final Path file;
    private List<Box> boxes=List.of();
    private final Map<String,Long> inspected=new LinkedHashMap<>();
    private long lastSaved;
    public ShulkerManifest(Path root,UUID account){file=root.resolve("shulker-manifest-"+account+".properties");Properties p=AtomicProperties.read(file);for(String k:p.stringPropertyNames())if(k.startsWith("inspection."))try{inspected.put(k.substring(11),Long.parseLong(p.getProperty(k)));}catch(NumberFormatException ignored){}if(inspected.size()>128)throw new IllegalStateException("Too many shulker inspections");}
    public List<Box> boxes(){return boxes;}
    public void observe(List<Box> current,long now){if(current.size()>36)throw new IllegalArgumentException("Too many carried boxes");boolean changed=!boxes.equals(current);boxes=List.copyOf(current);if(changed||now-lastSaved>60000){save();lastSaved=now;}}
    public boolean shouldInspect(String signature,long now){return now-inspected.getOrDefault(signature,-300000L)>=300000;}
    public void inspected(String signature,long now){inspected.put(signature,now);while(inspected.size()>128)inspected.remove(inspected.keySet().iterator().next());save();}
    public String summary(){return boxes.size()+" carried boxes; "+boxes.stream().filter(Box::known).count()+" with supplied contents; "+boxes.stream().filter(b->!b.known()).count()+" await safe inspection";}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("seen",""+lastSaved);p.setProperty("count",""+boxes.size());for(int i=0;i<boxes.size();i++){var b=boxes.get(i);String k="box."+i+".";p.setProperty(k+"slot",""+b.slot());p.setProperty(k+"item",b.item());p.setProperty(k+"signature",b.signature());p.setProperty(k+"known",""+b.known());b.contents().forEach((id,n)->p.setProperty(k+"item."+id,""+n));}inspected.forEach((k,v)->p.setProperty("inspection."+k,""+v));AtomicProperties.write(file,p);}
}

/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import java.nio.file.Path;
import java.util.*;
/** Per-server observed death outcome, never a guessed remote gamerule. Pending snapshots survive reconnects. */
public final class DeathInventory {
    public enum Outcome { UNKNOWN, PENDING, RETAINED, LOST, PARTIAL, EMPTY }
    private final Path file;
    private Properties values;
    public DeathInventory(Path root){file=root.resolve("bot-death-inventory.properties");values=AtomicProperties.read(file);}
    public long time(){return Long.parseLong(values.getProperty("time","0"));}
    public Outcome outcome(){return Outcome.valueOf(values.getProperty("outcome","UNKNOWN"));}
    public Map<String,Integer> before(){Map<String,Integer> result=new HashMap<>();for(String key:values.stringPropertyNames())if(key.startsWith("item."))result.put(key.substring(5),Integer.parseInt(values.getProperty(key)));return Map.copyOf(result);}
    public void record(long time,Map<String,Integer> items){Properties p=new Properties();p.setProperty("time",""+time);p.setProperty("outcome","PENDING");items.forEach((id,n)->{if(n>0)p.setProperty("item."+id,""+n);});save(p);}
    public static Map<String,Integer> missing(Map<String,Integer> before,Map<String,Integer> after){Map<String,Integer> result=new HashMap<>();before.forEach((id,n)->{int missing=Math.max(0,n-after.getOrDefault(id,0));if(missing>0)result.put(id,missing);});return Map.copyOf(result);}
    public static Outcome classify(Map<String,Integer> before,Map<String,Integer> after){if(before.isEmpty())return Outcome.EMPTY;var missing=missing(before,after);if(missing.isEmpty())return Outcome.RETAINED;return missing.equals(before)?Outcome.LOST:Outcome.PARTIAL;}
    public static boolean ready(String mode,boolean synced,long aliveFor,long stableFor){return aliveFor>=3000&&stableFor>=1500&&(mode.equals("keep")||mode.equals("drop")||synced);}
    public void finish(Outcome outcome){if(outcome==Outcome.PENDING||outcome==Outcome.UNKNOWN)throw new IllegalArgumentException("Not a death outcome");Properties p=new Properties();p.putAll(values);p.setProperty("outcome",outcome.name());save(p);}
    private void save(Properties p){AtomicProperties.write(file,p);values=p;}
}

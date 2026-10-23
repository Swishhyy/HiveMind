/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;

/** Durable at-most-once execution and per-authority ordering. Uncertain accepted work is never replayed. */
public final class CommandLedger {
    public enum Verdict { NEW, DUPLICATE, STALE }
    private final Path file;
    private final Map<UUID,Long> revisions=new LinkedHashMap<>();
    private final LinkedHashSet<UUID> seen=new LinkedHashSet<>();
    public CommandLedger(Path root,UUID account){file=root.resolve("command-ledger-"+account+".properties");Properties p=AtomicProperties.read(file);for(String k:p.stringPropertyNames())if(k.startsWith("source."))revisions.put(UUID.fromString(k.substring(7)),Long.parseLong(p.getProperty(k)));else if(k.startsWith("seen."))seen.add(UUID.fromString(p.getProperty(k)));if(revisions.size()>64||seen.size()>256)throw new IllegalStateException("Command ledger exceeds bounds");}
    public Verdict check(UUID source,UUID id,long revision){if(seen.contains(id))return Verdict.DUPLICATE;return revision<=revisions.getOrDefault(source,-1L)?Verdict.STALE:Verdict.NEW;}
    public void accept(UUID source,UUID id,long revision){if(check(source,id,revision)!=Verdict.NEW)throw new IllegalStateException("Command is already accepted or superseded");revisions.put(source,revision);while(revisions.size()>64)revisions.remove(revisions.keySet().iterator().next());seen.add(id);while(seen.size()>256)seen.remove(seen.iterator().next());Properties p=new Properties();p.setProperty("schema","1");revisions.forEach((k,v)->p.setProperty("source."+k,""+v));int i=0;for(UUID item:seen)p.setProperty("seen."+i++,item.toString());AtomicProperties.write(file,p);}
}

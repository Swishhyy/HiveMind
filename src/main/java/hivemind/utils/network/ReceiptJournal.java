/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.network.ControlPackets.*;
import java.nio.file.Path;
import java.util.*;

/** Persist proven order outcomes, never commands or inventory clicks. Reconnect may repeat an acknowledgement. */
public final class ReceiptJournal {
    private final Path file;
    private final UUID account;
    public ReceiptJournal(Path root,UUID account){file=root.resolve("order-receipts-"+account+".properties");this.account=account;}
    private LinkedHashMap<UUID,Receipt> read(){Properties p=AtomicProperties.read(file);int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>512)throw new IllegalStateException("Receipt journal exceeds bounds");LinkedHashMap<UUID,Receipt> result=new LinkedHashMap<>();for(int i=0;i<count;i++)try{String k="receipt."+i+".";var r=new Receipt(account,p.getProperty(k+"name"),UUID.fromString(p.getProperty(k+"id")),UUID.fromString(p.getProperty(k+"requester")),Boolean.parseBoolean(p.getProperty(k+"success")),p.getProperty(k+"message"),Long.parseLong(p.getProperty(k+"time")),Stage.valueOf(p.getProperty(k+"stage")));result.put(r.id(),r);}catch(RuntimeException ignored){}return result;}
    public void put(Receipt receipt){if(!receipt.reporter().equals(account)||receipt.stage()==Stage.APPLIED)return;var records=read();var previous=records.get(receipt.id());if(previous!=null&&Set.of(Stage.COMPLETED,Stage.CANCELLED,Stage.FAILED).contains(previous.stage())&&!Set.of(Stage.COMPLETED,Stage.CANCELLED,Stage.FAILED).contains(receipt.stage()))return;if(previous!=null&&previous.stage()==receipt.stage()&&(previous.message().equals(receipt.message())||receipt.observedAt()-previous.observedAt()<5000&&!Set.of(Stage.COMPLETED,Stage.CANCELLED,Stage.FAILED).contains(receipt.stage())))return;records.remove(receipt.id());records.put(receipt.id(),receipt);while(records.size()>512)records.remove(records.keySet().iterator().next());Properties p=new Properties();p.setProperty("schema","1");p.setProperty("count",""+records.size());int i=0;for(var r:records.values()){String k="receipt."+i+++".";p.setProperty(k+"name",r.reporterName());p.setProperty(k+"id",r.id().toString());p.setProperty(k+"requester",r.requester().toString());p.setProperty(k+"success",""+r.success());p.setProperty(k+"message",r.message());p.setProperty(k+"time",""+r.observedAt());p.setProperty(k+"stage",r.stage().name());}AtomicProperties.write(file,p);}
    public Receipt get(UUID id){return read().get(id);}
    public List<Receipt> latest(NetworkProtocol.Identity who,long now,int limit){return read().values().stream().sorted(Comparator.comparingLong(Receipt::observedAt).reversed()).limit(Math.max(0,Math.min(128,limit))).map(r->new Receipt(who.id(),who.name(),r.id(),r.requester(),r.success(),r.message(),now,r.stage())).toList();}
}

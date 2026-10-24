/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.network.ControlPackets.*;
import java.nio.file.Path;
import java.util.*;

/** Progress receipts refer to a durable order identity, including after a network or client restart. */
public final class OrderLifecycle {
    public record Active(UUID id,UUID requester,String command,Stage stage,String reason){}
    private final Path file;
    private Active active,suspended;
    public OrderLifecycle(Path root,UUID account){file=root.resolve("order-lifecycle-"+account+".properties");Properties p=AtomicProperties.read(file);active=read(p,"");suspended=read(p,"suspended.");}
    private static Active read(Properties p,String k){return p.containsKey(k+"id")?new Active(UUID.fromString(p.getProperty(k+"id")),UUID.fromString(p.getProperty(k+"requester")),p.getProperty(k+"command"),Stage.valueOf(p.getProperty(k+"stage")),p.getProperty(k+"reason","")):null;}
    public Active active(){return active;}public Active suspended(){return suspended;}
    public boolean terminal(){return active==null||Set.of(Stage.COMPLETED,Stage.CANCELLED,Stage.FAILED).contains(active.stage());}
    public void accepted(UUID id,UUID requester,String command){suspended=null;active=new Active(id,requester,command,Stage.ACCEPTED,"Order accepted");save();}
    public void interrupt(UUID id,UUID requester,String command){if(active!=null&&!terminal()&&!active.command().equals("recall"))suspended=new Active(active.id(),active.requester(),active.command(),Stage.BLOCKED,"Original task retained during recall");active=new Active(id,requester,command,Stage.ACCEPTED,"Recall accepted; retaining original task");save();}
    public boolean restore(){if(suspended==null)return false;active=new Active(suspended.id(),suspended.requester(),suspended.command(),Stage.STARTED,"Resuming the task retained during recall");suspended=null;save();return true;}
    public Receipt cancelSuspended(NetworkProtocol.Identity who,long now,String reason){if(suspended==null)return null;var receipt=new Receipt(who.id(),who.name(),suspended.id(),suspended.requester(),true,reason,now,Stage.CANCELLED);suspended=null;save();return receipt;}
    public boolean update(Stage stage,String reason){if(active==null||active.stage()==stage&&active.reason().equals(reason)||terminal())return false;active=new Active(active.id(),active.requester(),active.command(),stage,reason.substring(0,Math.min(500,reason.length())));save();return true;}
    public Receipt receipt(NetworkProtocol.Identity who,long now){return receipt(active,who,now);}
    public Receipt suspendedReceipt(NetworkProtocol.Identity who,long now){return receipt(suspended,who,now);}
    private static Receipt receipt(Active value,NetworkProtocol.Identity who,long now){return value==null?null:new Receipt(who.id(),who.name(),value.id(),value.requester(),value.stage()!=Stage.FAILED,value.reason(),now,value.stage());}
    private static void write(Properties p,String k,Active value){if(value==null)return;p.setProperty(k+"id",value.id().toString());p.setProperty(k+"requester",value.requester().toString());p.setProperty(k+"command",value.command());p.setProperty(k+"stage",value.stage().name());p.setProperty(k+"reason",value.reason());}
    private void save(){Properties p=new Properties();p.setProperty("schema","2");write(p,"",active);write(p,"suspended.",suspended);AtomicProperties.write(file,p);}
}

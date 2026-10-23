/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;

/** Write-ahead inventory intent. Recovery reconciles real contents; it never repeats an uncertain click. */
public final class InventoryTransaction {
    public record Pending(UUID id,String owner,String operation,int menu,String before,long started,int clicks) {}
    private final Path file;
    private Pending pending;
    private final List<Pending> interrupted=new ArrayList<>();
    private String outcome="No pending inventory work";
    public InventoryTransaction(Path root,UUID account){
        file=root.resolve("inventory-transaction-"+account+".properties");Properties p=AtomicProperties.read(file);
        if(p.containsKey("id"))pending=new Pending(UUID.fromString(p.getProperty("id")),p.getProperty("owner"),p.getProperty("operation"),Integer.parseInt(p.getProperty("menu")),p.getProperty("before"),Long.parseLong(p.getProperty("started")),Integer.parseInt(p.getProperty("clicks","1")));
        int count=Math.min(16,Integer.parseInt(p.getProperty("interrupted","0")));for(int i=0;i<count;i++){String k="interrupted."+i+".";interrupted.add(new Pending(UUID.fromString(p.getProperty(k+"id")),p.getProperty(k+"owner"),p.getProperty(k+"operation"),Integer.parseInt(p.getProperty(k+"menu")),p.getProperty(k+"before"),Long.parseLong(p.getProperty(k+"started")),Integer.parseInt(p.getProperty(k+"clicks","1"))));}
        if(pending!=null)outcome="Interrupted "+pending.operation()+"; waiting for server inventory reconciliation";
    }
    public Pending pending(){return pending;}public String outcome(){return outcome;}
    public void before(String owner,String operation,int menu,String fingerprint,long now){
        if(pending!=null&&(pending.menu()!=menu||!pending.owner().equals(owner))){interrupted.add(pending);while(interrupted.size()>16)interrupted.remove(0);pending=null;}
        if(pending!=null){
            // Multi-click swaps form one transaction. Their baseline remains the pre-swap inventory.
            pending=new Pending(pending.id(),owner,pending.operation(),menu,pending.before(),pending.started(),pending.clicks()+1);
        }else pending=new Pending(UUID.randomUUID(),owner,operation,menu,fingerprint,now,1);
        outcome="Awaiting server confirmation: "+operation;save();
    }
    public void confirmed(int menu,String fingerprint,boolean cursorEmpty){
        if(pending==null||!cursorEmpty||pending.menu()!=menu&&menu!=0)return;
        outcome=pending.before().equals(fingerprint)?"Server inventory reconciled; attempted action made no inventory change":"Server inventory changed; task must verify its requested result";
        pending=null;save();
    }
    public void reconciled(String fingerprint,boolean cursorEmpty){if(cursorEmpty&&(pending!=null||!interrupted.isEmpty())){outcome=pending!=null&&pending.before().equals(fingerprint)?"Recovered inventory unchanged; task may plan a fresh attempt":"Recovered actual inventory after interruption";pending=null;interrupted.clear();save();}}
    public boolean permits(String owner,int menu){return pending==null||pending.owner().equals(owner)&&pending.menu()==menu;}
    private static void put(Properties p,String k,Pending n){p.setProperty(k+"id",n.id().toString());p.setProperty(k+"owner",n.owner());p.setProperty(k+"operation",n.operation());p.setProperty(k+"menu",""+n.menu());p.setProperty(k+"before",n.before());p.setProperty(k+"started",""+n.started());p.setProperty(k+"clicks",""+n.clicks());}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");if(pending!=null)put(p,"",pending);p.setProperty("interrupted",""+interrupted.size());for(int i=0;i<interrupted.size();i++)put(p,"interrupted."+i+".",interrupted.get(i));AtomicProperties.write(file,p);}
}

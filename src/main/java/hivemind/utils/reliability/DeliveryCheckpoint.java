/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** One durable handoff. An uncertain dropped stack is never automatically sent twice. */
public final class DeliveryCheckpoint {
    public record Delivery(UUID recipient,String name,String item,int quantity,int delivered,int pending,Endpoint point) {
        public Delivery {if(recipient==null||!name.matches("[A-Za-z0-9_]{1,16}")||!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||quantity<1||quantity>4096||delivered<0||pending<0||delivered+pending>quantity)throw new IllegalArgumentException("Invalid delivery checkpoint");}
    }
    private final Path file;
    private Delivery value;
    public DeliveryCheckpoint(Path root,UUID account){file=root.resolve("delivery-runtime-"+account+".properties");Properties p=AtomicProperties.read(file);if(p.containsKey("recipient"))value=new Delivery(UUID.fromString(p.getProperty("recipient")),p.getProperty("name"),p.getProperty("item"),Integer.parseInt(p.getProperty("quantity")),Integer.parseInt(p.getProperty("delivered")),Integer.parseInt(p.getProperty("pending")),new Endpoint(p.getProperty("dimension"),Integer.parseInt(p.getProperty("x")),Integer.parseInt(p.getProperty("y")),Integer.parseInt(p.getProperty("z"))));}
    public Delivery value(){return value;}
    public void start(UUID recipient,String name,String item,int quantity,Endpoint point){if(uncertain())throw new IllegalStateException("Previous handoff pickup is unconfirmed; inspect it and use deliver resolve received/returned/lost");value=new Delivery(recipient,name,item,quantity,0,0,point);save();}
    public boolean uncertain(){return value!=null&&value.pending()>0;}
    public void dropping(int count){if(value==null||uncertain()||count<1||count>value.quantity()-value.delivered())throw new IllegalStateException("Handoff already pending or invalid");replace(value.delivered(),count);}
    public void received(int count){if(value==null||count<1||count>value.pending())throw new IllegalArgumentException("Pickup exceeds pending handoff");replace(value.delivered()+count,value.pending()-count);}
    public void returned(){if(uncertain())replace(value.delivered(),0);}
    public void resolve(String result){if(!uncertain())throw new IllegalStateException("No uncertain handoff to resolve");switch(result){case "received"->received(value.pending());case "returned","lost"->returned();default->throw new IllegalArgumentException("Use received, returned or lost after inspecting the handoff");}}
    private void replace(int delivered,int pending){value=new Delivery(value.recipient(),value.name(),value.item(),value.quantity(),delivered,pending,value.point());save();}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");var d=value;p.setProperty("recipient",d.recipient().toString());p.setProperty("name",d.name());p.setProperty("item",d.item());p.setProperty("quantity",""+d.quantity());p.setProperty("delivered",""+d.delivered());p.setProperty("pending",""+d.pending());p.setProperty("dimension",d.point().dimension());p.setProperty("x",""+d.point().x());p.setProperty("y",""+d.point().y());p.setProperty("z",""+d.point().z());AtomicProperties.write(file,p);}
}

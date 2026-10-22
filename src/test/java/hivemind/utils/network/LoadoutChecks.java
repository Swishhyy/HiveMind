/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.*;
import java.util.*;
public final class LoadoutChecks {
    private static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    private static void rejects(Runnable r){try{r.run();throw new AssertionError("Invalid base accepted");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Loadout/base checks passed: refill bands, category totals, quiet-period resets, durable scoped bases and legacy policy migration");}
    public static void runAll()throws Exception{
        var targets=SupplyReserves.targets(256,128,2,4);check(targets.get("@rockets")==256&&targets.get("@food")==128&&targets.get("@elytra")==2&&targets.get("@totems")==4,"Loadout defaults must fill the requested expedition reserves");
        check(SupplyReserves.missing(targets,Map.of("@rockets",128,"@food",64,"@elytra",2,"@totems",3)).isEmpty(),"Using one item must not cause constant box placement while within reserve bands");
        var low=SupplyReserves.missing(targets,Map.of("@rockets",127,"@food",63,"@elytra",1,"@totems",2));check(low.equals(targets),"Crossing the lower band must refill all low categories to their upper targets");
        check(!SupplyReserves.satisfied(targets,Map.of("@rockets",256,"@food",128,"@elytra",2,"@totems",3)),"A partial first box must not be mistaken for complete resupply");
        check(SupplyReserves.satisfied(targets,targets),"Satisfied targets should finish without another box");
        for(String key:List.of("@rockets","@food")){int min=SupplyReserves.minimum(key,targets.get(key));for(int start=0;start<min;start++){int count=start;while(!SupplyReserves.refilled(key,targets.get(key),count))count+=64;check(count>=min&&count<=targets.get(key),"Whole-stack refills must remain inside the requested band");}}
        SupplyReserves.QuietPeriod quiet=new SupplyReserves.QuietPeriod();check(!quiet.ready(true,0)&&!quiet.ready(true,4999)&&quiet.ready(true,5000),"Placement requires a continuous quiet window");check(!quiet.ready(false,5001)&&!quiet.ready(true,5002)&&!quiet.ready(true,10000)&&quiet.ready(true,10002),"An approaching threat must reset the quiet window");
        Path root=Files.createTempDirectory("hivemind-base");try{var store=new BotBaseStore(root);var point=new Endpoint("minecraft:overworld",-10,64,20);var base=new BotBaseStore.Base(point,16);store.set(base);check(new BotBaseStore(root).get().equals(base),"Base must survive client restart");check(base.contains(new Endpoint(point.dimension(),6,80,4))&&!base.contains(new Endpoint(point.dimension(),7,64,20))&&!base.contains(new Endpoint("minecraft:the_nether",-10,64,20)),"Base scans must respect radius and dimension bounds");rejects(()->new BotBaseStore.Base(point,33));store.set(null);check(store.get()==null,"Clearing the base must persist");
            Properties p=new Properties();p.setProperty("version","1");p.setProperty("global.setting.botExpeditionRockets","32");p.setProperty("global.setting.botExpeditionTotems","1");AtomicProperties.write(root.resolve("bot-network-controls.properties"),p);var rules=new ControlRules(root);check(rules.settings(null).get("botExpeditionRockets").equals("256")&&rules.settings(null).get("botExpeditionTotems").equals("4")&&rules.version()>1,"Old global policies must not undo the new requested reserve defaults");check(new ControlRules(root).version()==rules.version(),"Migration must be persisted once rather than resyncing forever");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}

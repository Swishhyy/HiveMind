/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import java.util.*;

public final class ResupplyChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        Map<String,Integer> kit=Map.of("@rockets",256,"@food",128,"@elytra",2,"@totems",4,"@armor_chestplate",1);
        Map<String,Integer> loose=new HashMap<>(Map.of("@rockets",128,"@food",64,"@elytra",1,"@totems",3));
        // Launching spends a rocket; that and a missing spare wing/chestplate cannot immediately order a landing.
        for(int rockets=127;rockets>=32;rockets--){
            loose.put("@rockets",rockets);
            check(ResupplyPolicy.flightTargets(kit,loose,8).isEmpty(),"Healthy roam flight landed for a reserve top-up/spare gear");
        }
        loose.put("@rockets",15);
        check(ResupplyPolicy.flightTargets(kit,loose,8).equals(Map.of("@rockets",256)),"Near-reserve rockets require a carried refill");
        loose.put("@rockets",40);
        check(ResupplyPolicy.flightTargets(kit,loose,40).containsKey("@rockets"),"Configured safety reserve must influence refuel timing");
        loose.put("@rockets",127);
        var boxed=Map.of("@rockets",1728,"@food",1728,"@elytra",2,"@totems",27);
        var plan=ResupplyPolicy.plan(kit,loose,boxed,false);
        check(plan.portable().containsKey("@rockets")&&plan.portable().containsKey("@elytra")&&plan.storage().isEmpty(),"Carried boxed supplies must precede an external trip");
        check(!plan.portable().containsKey("@armor_chestplate"),"Do not invent armor in a box that has none");

        loose.put("@rockets",256);loose.put("@food",128);
        check(ResupplyPolicy.plan(kit,loose,Map.of(),false).storage().isEmpty(),"A spare wing/armor shortage alone must not force a roam base trip");
        check(ResupplyPolicy.plan(kit,loose,Map.of(),true).storage().containsKey("@armor_chestplate"),"Hunt preparation retains armor requirements");
        var oneWing=Map.of("@elytra",2);
        check(ResupplyPolicy.plan(oneWing,Map.of(),Map.of("@elytra",1),false).portable().containsKey("@elytra"),"Unpack the single usable carried wing first");
        check(ResupplyPolicy.plan(oneWing,Map.of("@elytra",1),Map.of(),false).storage().isEmpty(),"A partial wing refill must not immediately become a backup-wing storage trip");

        loose.put("@rockets",8);
        check(ResupplyPolicy.plan(kit,loose,Map.of(),false).storage().get("@rockets")==256,"A genuine unboxed consumable shortage may return to storage");
        var partial=ResupplyPolicy.plan(kit,loose,Map.of("@rockets",32),false);
        check(partial.portable().containsKey("@rockets")&&partial.storage().isEmpty(),"Even a partial carried supply is used before external stock");
        check(ResupplyPolicy.plan(Map.of("@blocks",128),Map.of("@blocks",127),Map.of(),false).storage().isEmpty(),"Placing one block must not trigger a mining supply trip");
        check(ResupplyPolicy.plan(Map.of("@blocks",128),Map.of("@blocks",63),Map.of(),false).storage().containsKey("@blocks"),"A low building reserve still gets replenished");

        var retry=new ResupplyPolicy.Retry();var requested=Map.of("@rockets",256);
        retry.finished(requested,Map.of("@rockets",8),1000);
        check(retry.ready(requested,Map.of("@rockets",8),61000).isEmpty(),"Empty storage must not be retried after only one minute");
        check(retry.ready(requested,Map.of("@rockets",9),61000).equals(requested),"Confirmed new supplies allow reevaluation");
        check(retry.ready(requested,Map.of("@rockets",8),601000).equals(requested),"Unchanged stock eventually gets a bounded retry");
        retry.finished(requested,Map.of("@rockets",256),602000);
        check(retry.ready(requested,Map.of("@rockets",8),603000).equals(requested),"A successful refill clears an old shortage");
        System.out.println("Resupply checks passed: stable roam flight, urgent consumables, carried-first planning, partial boxes, optional gear, refill bands and shortage cooldown");
    }
}

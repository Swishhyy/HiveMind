/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.social.ClutchPolicy;
import java.nio.file.*;
import java.util.*;

public final class CombatSupplyChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Combat/supply checks passed: recovery ownership, durable portable-box checkpoints, bounded chest-box unpacking, reserved pickup space and fast-fall clutch windows");}
    public static void runAll()throws Exception{
        var boxes=new StockBoxTrip();
        check(!boxes.canBorrow(1)&&boxes.canBorrow(2),"Storage withdrawal needs room for the box and a loose-supply slot");
        boxes.confirmedWithdrawal(2);boxes.beginUnpack(Map.of("@rockets",0,"@elytra",1));
        check(!boxes.canBorrow(10),"Do not borrow another box before current unpacking ends");
        check(boxes.finishUnpack(Map.of("@rockets",64,"@elytra",1),false)==StockBoxTrip.Result.PROGRESS,"Only a loose inventory increase credits successful unpacking");
        boxes.confirmedWithdrawal(2);boxes.beginUnpack(Map.of("@rockets",64));
        check(boxes.finishUnpack(Map.of("@rockets",64),false)==StockBoxTrip.Result.NO_PROGRESS,"An emptied/refused box must stop repeated borrowing");
        boxes.beginUnpack(Map.of("@rockets",64));check(boxes.finishUnpack(Map.of("@rockets",128),true)==StockBoxTrip.Result.RECOVERY_REQUIRED,"Getting supplies does not complete ownership until the box is recovered");
        for(int i=boxes.borrowed();i<8;i++){boxes.confirmedWithdrawal(2);boxes.beginUnpack(Map.of("@food",i));check(boxes.finishUnpack(Map.of("@food",i+1),false)==StockBoxTrip.Result.PROGRESS,"Multiple category boxes can refill in sequence");}
        check(!boxes.canBorrow(36),"A trip cannot drain unlimited boxes into its inventory");boxes.reset();check(boxes.canBorrow(2)&&boxes.borrowed()==0,"A new trip resets its bounded box budget");
        check(PortableSupplyPolicy.canWithdraw(64,0,2,64),"A free supply slot plus a reserved box slot can take a full stack");
        check(!PortableSupplyPolicy.canWithdraw(64,0,1,64),"The box's only recovery slot must not be consumed by supplies");
        check(PortableSupplyPolicy.canWithdraw(32,32,1,64),"Merging a whole stack preserves the last empty slot for the box");
        check(!PortableSupplyPolicy.canWithdraw(64,63,1,64),"A partial merge must not spill into the reserved box slot");
        check(!PortableSupplyPolicy.canWithdraw(64,64,0,64),"Even a full merge is insufficient when no box pickup slot remains");
        check(!PortableSupplyPolicy.mayRecover(false,true,true)&&!PortableSupplyPolicy.mayRecover(true,false,true)
                &&!PortableSupplyPolicy.mayRecover(true,true,false)&&PortableSupplyPolicy.mayRecover(true,true,true),"Only a confirmed matching owned box with a clear cursor may be recovered");
        check(ClutchPolicy.impactTicks(5,-3.8)<=2,"A fast fall crosses its final five blocks in at most two ticks; the ordinary four-tick use delay is too slow");
        check(ClutchPolicy.armHeight(4.5,-3.8)>ClutchPolicy.armHeight(4.5,-.3),"Fast falls must aim earlier than slow falls");
        check(ClutchPolicy.descent(-3.8,3)>11&&ClutchPolicy.descent(-.3,3)<2,"The arm window accounts for actual descent rather than a fixed height");
        check(!ClutchPolicy.caught(false,true,-1,4)&&!ClutchPolicy.caught(false,true,-.05,1),"Touching water at speed must not immediately pick the source back up");
        check(ClutchPolicy.caught(false,true,-.05,4)&&ClutchPolicy.caught(true,false,0,0),"Recovery waits for water to catch the fall or an actual landing");
        Path root=Files.createTempDirectory("portable-supplies");
        try{
            PortableBoxStore store=new PortableBoxStore(root);Endpoint point=new Endpoint("minecraft:overworld",12,64,-40);
            var attempted=new PortableBoxStore.Box(point,"minecraft:blue_shulker_box",false);store.save(attempted);
            check(new PortableBoxStore(root).box().equals(attempted),"An outstanding placement location survives a crash without being promoted to owned");
            var owned=new PortableBoxStore.Box(point,attempted.item(),true);store.save(owned);
            check(new PortableBoxStore(root).box().equals(owned),"Server-confirmed recovery ownership survives a client restart");
            check(new PortableBoxStore(root.resolve("other-server")).box()==null,"A server's portable box is never recovered in another server");
            try{new PortableBoxStore.Box(point,"minecraft:chest",true);throw new AssertionError("Unrelated container accepted as a portable box");}catch(IllegalArgumentException expected){}
            store.save(null);check(new PortableBoxStore(root).box()==null,"Confirmed pickup clears the outstanding recovery checkpoint");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}

/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import java.util.*;
/** Emergency care uses category matching too, so loose and boxed food share the same storage route. */
public final class EmergencySupplies {
    private EmergencySupplies(){}
    public static Map<String,Integer> targets(boolean food,int minimumFood,int restockFood,boolean mining,boolean blocks,int minimumBlocks,int restockBlocks,boolean pickaxe,boolean debris){
        var result=new LinkedHashMap<String,Integer>();
        if(food)result.put("@food",Math.max(1,Math.min(128,Math.max(minimumFood,restockFood))));
        if(mining&&blocks)result.put("@blocks",Math.max(1,Math.min(256,Math.max(minimumBlocks,restockBlocks))));
        if(mining&&pickaxe)result.put(debris?"@debris_pickaxe":"@pickaxe",1);
        return Map.copyOf(result);
    }
}

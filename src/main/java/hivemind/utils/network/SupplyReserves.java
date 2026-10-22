/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import java.util.*;
/** Category quantities and refill hysteresis shared by portable and fixed stock. */
public final class SupplyReserves {
    public static final String ROCKETS="@rockets",FOOD="@food",ELYTRA="@elytra",TOTEMS="@totems",BLOCKS="@blocks";
    public static final class QuietPeriod {private long since;private boolean observed;public void reset(){observed=false;}public boolean ready(boolean safe,long now){if(!safe){reset();return false;}if(!observed){observed=true;since=now;}return now-since>=5000;}}
    private SupplyReserves(){}
    public static Map<String,Integer> targets(int rockets,int food,int wings,int totems){return Map.of(ROCKETS,Math.max(128,Math.min(256,rockets)),FOOD,Math.max(64,Math.min(128,food)),ELYTRA,Math.max(2,Math.min(4,wings)),TOTEMS,Math.max(3,Math.min(4,totems)));}
    public static int minimum(String category,int target){return switch(category){case ROCKETS->Math.min(128,target);case FOOD->Math.min(64,target);case TOTEMS->Math.min(3,target);case ELYTRA->target;default->target;};}
    public static Map<String,Integer> missing(Map<String,Integer> targets,Map<String,Integer> counts){Map<String,Integer> result=new LinkedHashMap<>();targets.forEach((key,target)->{if(counts.getOrDefault(key,0)<minimum(key,target))result.put(key,target);});return Map.copyOf(result);}
    /** Leave room for the last whole 64-item stack so refills stay inside the requested bands. */
    public static int refillGoal(String category,int target){return category.equals(ROCKETS)||category.equals(FOOD)?Math.max(minimum(category,target),target-63):target;}
    public static boolean refilled(String category,int target,int count){return count>=refillGoal(category,target);}
    public static boolean satisfied(Map<String,Integer> targets,Map<String,Integer> counts){return targets.entrySet().stream().allMatch(e->refilled(e.getKey(),e.getValue(),counts.getOrDefault(e.getKey(),0)));}
}

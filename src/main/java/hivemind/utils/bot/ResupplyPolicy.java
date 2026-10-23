/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.network.SupplyReserves;
import java.util.*;

/** Desired kit stock, usable loose stock and carried boxed stock serve different decisions. */
public final class ResupplyPolicy {
    public record Plan(Map<String,Integer> portable, Map<String,Integer> storage) {
        public Plan { portable=Map.copyOf(portable);storage=Map.copyOf(storage); }
    }
    private ResupplyPolicy() {}

    public static int minimum(String key,int target) {
        return switch(key) {
            case "@blocks" -> Math.min(64,target);
            case "@crystals","@obsidian","@glowstone" -> Math.min(8,target);
            case "@anchors" -> Math.min(4,target);
            default -> SupplyReserves.minimum(key,target);
        };
    }
    public static Map<String,Integer> missing(Map<String,Integer> targets,Map<String,Integer> loose) {
        Map<String,Integer> result=new LinkedHashMap<>();
        targets.forEach((key,target)->{if(loose.getOrDefault(key,0)<minimum(key,target))result.put(key,target);});
        return Map.copyOf(result);
    }
    public static Plan plan(Map<String,Integer> targets,Map<String,Integer> loose,Map<String,Integer> boxed,boolean hunting) {
        Map<String,Integer> portable=new LinkedHashMap<>(),storage=new LinkedHashMap<>();
        missing(targets,loose).forEach((key,target)->{
            int available=boxed.getOrDefault(key,0);
            if(available>0)portable.put(key,target);
            if((long)loose.getOrDefault(key,0)+available<minimum(key,target))storage.put(key,target);
        });
        // Use carried boxes first, even when their contents satisfy only part of the kit.
        if(!portable.isEmpty())return new Plan(portable,Map.of());
        boolean essential=storage.keySet().stream().anyMatch(key->
                key.equals("@elytra")?loose.getOrDefault(key,0)==0:!key.startsWith("@armor_")||hunting);
        return new Plan(Map.of(),essential?storage:Map.of());
    }
    /** Mid-flight top-ups are for imminent consumable shortages, never a missing spare wing or armor piece. */
    public static Map<String,Integer> flightTargets(Map<String,Integer> targets,Map<String,Integer> loose,int flightReserve) {
        Map<String,Integer> urgent=new LinkedHashMap<>();
        int rocketFloor=Math.max(16,flightReserve+8);
        if(targets.containsKey("@rockets")&&loose.getOrDefault("@rockets",0)<Math.min(minimum("@rockets",targets.get("@rockets")),rocketFloor))
            urgent.put("@rockets",targets.get("@rockets"));
        if(targets.containsKey("@food")&&loose.getOrDefault("@food",0)<Math.min(8,minimum("@food",targets.get("@food"))))
            urgent.put("@food",targets.get("@food"));
        return Map.copyOf(urgent);
    }

    /** An unchanged unavailable item must not send the bot on the same empty storage trip every minute. */
    public static final class Retry {
        private record Failure(int available,long until) {}
        private final Map<String,Failure> failed=new HashMap<>();
        public Map<String,Integer> ready(Map<String,Integer> requested,Map<String,Integer> available,long now) {
            Map<String,Integer> result=new LinkedHashMap<>();
            requested.forEach((key,target)->{var last=failed.get(key);if(last==null||now>=last.until()||available.getOrDefault(key,0)>last.available())result.put(key,target);});
            return Map.copyOf(result);
        }
        public void finished(Map<String,Integer> requested,Map<String,Integer> available,long now) {
            requested.forEach((key,target)->{int count=available.getOrDefault(key,0);if(count<minimum(key,target))failed.put(key,new Failure(count,now+600000));else failed.remove(key);});
        }
        public void clear(){failed.clear();}
    }
}

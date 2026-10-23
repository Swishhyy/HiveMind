/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.network.SupplyReserves;
import java.nio.file.Path;
import java.util.*;

/** Account-scoped kit preferences. Quantities describe desired carried stock, never an order to open unverified storage. */
public final class TaskLoadoutStore {
    public enum Mode { ROAM, MINE, NETHER, HUNT }
    private static final Set<String> CATEGORIES=Set.of("@food","@rockets","@elytra","@totems","@blocks","@pickaxe","@debris_pickaxe","@weapon","@crystals","@obsidian","@anchors","@glowstone","@armor_helmet","@armor_chestplate","@armor_leggings","@armor_boots");
    private final Path file;
    private final Properties overrides;
    public TaskLoadoutStore(Path root,UUID account){file=root.resolve("bot-loadouts-"+account+".properties");overrides=AtomicProperties.read(file);}
    public static String category(String input){String result=input.startsWith("@")?input.toLowerCase(Locale.ROOT):"@"+input.toLowerCase(Locale.ROOT);if(!CATEGORIES.contains(result))throw new IllegalArgumentException("Unknown kit item; use food, rockets, elytra, totems, blocks, pickaxe, debris_pickaxe, weapon, crystals, obsidian, anchors, glowstone, or armor_<slot>");return result;}
    public static Mode mode(String input){try{return Mode.valueOf(input.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException error){throw new IllegalArgumentException("Choose roam, mine, nether or hunt");}}
    public void set(Mode mode,String item,int count){String key=category(item);if(count<0||count>512)throw new IllegalArgumentException("Kit quantity must be 0–512");overrides.setProperty(mode.name()+"."+key,""+count);AtomicProperties.write(file,overrides);}
    public void reset(Mode mode){overrides.stringPropertyNames().stream().filter(key->key.startsWith(mode.name()+".")).toList().forEach(overrides::remove);AtomicProperties.write(file,overrides);}
    public Map<String,Integer> targets(Mode mode,Map<String,Integer> base,boolean debris,boolean anchorAllowed,String combatStyle,int blocks){
        Map<String,Integer> wanted=new LinkedHashMap<>(base);
        if(mode==Mode.MINE||mode==Mode.NETHER){wanted.put(SupplyReserves.BLOCKS,Math.max(64,Math.min(256,blocks)));wanted.put(debris?"@debris_pickaxe":"@pickaxe",1);}
        if(mode==Mode.NETHER)wanted.merge(SupplyReserves.FOOD,128,Math::max);
        if(mode==Mode.HUNT){wanted.put("@weapon",1);if(base.containsKey(SupplyReserves.TOTEMS))wanted.merge(SupplyReserves.TOTEMS,4,Math::max);
            if(!"melee".equals(combatStyle)){
                if(!"anchor".equals(combatStyle)){wanted.put("@crystals",16);wanted.put("@obsidian",16);}
                if(anchorAllowed&&!"crystal".equals(combatStyle)){wanted.put("@anchors",8);wanted.put("@glowstone",16);}
            }
        }
        for(String key:overrides.stringPropertyNames())if(key.startsWith(mode.name()+".")){
            String item=key.substring(mode.name().length()+1);if(!CATEGORIES.contains(item))continue;
            try{int count=Integer.parseInt(overrides.getProperty(key));if(count==0)wanted.remove(item);else if(count>0&&count<=512)wanted.put(item,count);}catch(NumberFormatException ignored){}
        }
        // Never request anchors in a dimension where they cannot be used explosively.
        if(!anchorAllowed){wanted.remove("@anchors");wanted.remove("@glowstone");}
        return Map.copyOf(wanted);
    }
}

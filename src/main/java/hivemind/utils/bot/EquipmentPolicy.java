/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
/** Dependency-free vanilla armor ranking and durability boundaries. */
public final class EquipmentPolicy {
    private EquipmentPolicy(){}
    public static String category(String id){
        if(!id.startsWith("minecraft:"))return "";
        for(String suffix:new String[]{"helmet","chestplate","leggings","boots"})if(id.endsWith("_"+suffix))return "@armor_"+suffix;
        return "";
    }
    public static boolean usable(int remaining,int maximum){return maximum>0&&remaining>Math.max(10,maximum/6);}
    public static double score(String id,int remaining,int maximum,int protection,boolean binding){
        if(category(id).isEmpty()||binding||!usable(remaining,maximum))return -1;
        int tier=id.contains(":netherite_")?7:id.contains(":diamond_")?6:id.contains(":iron_")?5:id.contains(":turtle_")?5:id.contains(":copper_")?4:id.contains(":chainmail_")?3:id.contains(":golden_")?2:1;
        return tier*100+Math.min(20,Math.max(0,protection))*30+Math.min(1,remaining/(double)maximum);
    }
}

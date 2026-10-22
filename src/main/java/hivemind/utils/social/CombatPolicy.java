/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.social;

public final class CombatPolicy {
    private CombatPolicy() {}
    public static boolean fightMob(boolean armed,float health,int hunger,int nearby,boolean dangerous,boolean playerThreat) {
        return armed&&health>8&&hunger>6&&nearby<=3&&!dangerous&&!playerThreat;
    }
    /** Estimate weapon usefulness from vanilla material, type and Sharpness. */
    public static double weaponScore(String path,boolean sword,boolean axe,int sharpness,boolean shield) {
        if(!sword&&!axe)return -1;
        int tier=path.startsWith("netherite_")?5:path.startsWith("diamond_")?4:path.startsWith("iron_")?3:
                path.startsWith("stone_")?2:path.startsWith("copper_")?2:1;
        double damage=sword?tier+3:tier>=4?9:tier>=2?9:7;
        if(axe&&tier==5)damage=10;
        damage+=sharpness>0?.5+sharpness*.5:0;
        double speed=sword?1.6:path.startsWith("golden_")||tier>=4?1:tier==3?.9:.8;
        return damage*speed+(shield&&axe?100:0);
    }
}

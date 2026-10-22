/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.utils.social;

/** Conservative vanilla damage estimates and explicit limits; server plugins can change actual damage. */
public final class BlastPolicy {
    private BlastPolicy() {}
    public static boolean anchorDimension(String id) { return id.equals("minecraft:overworld")||id.equals("minecraft:the_end"); }
    public static double rawDamage(double power,double distance,double exposure) {
        if(!Double.isFinite(power)||!Double.isFinite(distance)||!Double.isFinite(exposure)||power<=0||distance<0)return Double.POSITIVE_INFINITY;
        double radius=power*2;if(distance>radius)return 0;
        double impact=(1-distance/radius)*Math.max(0,Math.min(1,exposure));
        return Math.floor((impact*impact+impact)*0.5*7*radius+1);
    }
    /** Explosions scale with difficulty for every player: none on peaceful, halved plus one on easy, half again on hard. */
    public static double difficultyScaled(double raw,int difficulty) {
        if(!Double.isFinite(raw))return Double.POSITIVE_INFINITY;
        return switch(difficulty) { case 0 -> 0; case 1 -> Math.min(raw/2+1,raw); case 3 -> raw*1.5; default -> raw; };
    }
    public static double afterArmor(double raw,double armor,double toughness,int protection) {
        if(!Double.isFinite(raw)||!Double.isFinite(armor)||!Double.isFinite(toughness)||raw<0)return Double.POSITIVE_INFINITY;
        double reduction=Math.min(20,Math.max(Math.max(0,armor)*0.2,armor-raw/(2+Math.max(0,toughness)/4)));
        double armored=raw*(1-reduction/25);
        return armored*(1-Math.max(0,Math.min(20,protection))/25.0);
    }
    public static double afterResistance(double damage,int amplifier) {
        if(!Double.isFinite(damage)||damage<0)return Double.POSITIVE_INFINITY;
        return damage*(1-Math.max(0,Math.min(5,(long)amplifier+1))*0.2);
    }
    /** A small relative reserve buffer must not turn a one-point chip into three points of damage. */
    public static double damageMargin(double damage) { return Math.min(1,Math.max(0,damage)*0.05+0.25); }
    public static boolean blockedByCube(double x,double y,double z,double ax,double ay,double az,double bx,double by,double bz) {
        double enter=0,exit=1;double[] minimum={x+0.0001,y+0.0001,z+0.0001},maximum={x+0.9999,y+0.9999,z+0.9999};
        double[] from={ax,ay,az},delta={bx-ax,by-ay,bz-az};
        for(int axis=0;axis<3;axis++) {
            if(Math.abs(delta[axis])<1e-10) { if(from[axis]<minimum[axis]||from[axis]>maximum[axis])return false; }
            else {
                double a=(minimum[axis]-from[axis])/delta[axis],b=(maximum[axis]-from[axis])/delta[axis];
                enter=Math.max(enter,Math.min(a,b));exit=Math.min(exit,Math.max(a,b));if(enter>exit)return false;
            }
        }return exit>=0&&enter<=1;
    }
    public static boolean mayBlast(double selfDamage,double targetDamage,float selfHealth,float absorption,float targetHealth,
                                   int maximumSelf,int minimumTarget,int reserve,boolean bystanders,boolean protectedArea) {
        return Double.isFinite(selfDamage)&&Double.isFinite(targetDamage)&&selfDamage>=0&&targetDamage>=0
                &&!bystanders&&!protectedArea&&selfDamage<=StealthPolicy.bounded(maximumSelf,1,20)
                &&(targetDamage>=StealthPolicy.bounded(minimumTarget,1,20)||targetHealth>0&&targetDamage>=targetHealth)
                &&selfHealth+Math.max(0,absorption)-selfDamage-damageMargin(selfDamage)>=StealthPolicy.bounded(reserve,2,16)
                &&(targetDamage>=selfDamage*0.8||targetDamage>=targetHealth);
    }
}

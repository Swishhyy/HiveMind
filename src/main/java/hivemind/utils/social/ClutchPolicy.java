/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.social;

/** Vanilla falling acceleration estimates, used to aim before a short placement window. */
public final class ClutchPolicy {
    private ClutchPolicy(){}
    public static double descent(double velocity,int ticks){
        double distance=0;
        for(int i=0;i<Math.max(0,Math.min(80,ticks));i++){distance+=Math.max(0,-velocity);velocity=(velocity-.08)*.98;}
        return distance;
    }
    public static int impactTicks(double height,double velocity){
        for(int i=1;i<=80;i++)if(descent(velocity,i)>=height)return i;
        return 80;
    }
    public static double armHeight(double reach,double velocity){return Math.max(12,Math.min(48,reach+descent(velocity,3)+2));}
    public static boolean caught(boolean grounded,boolean inWater,double velocity,int wetTicks){return grounded||inWater&&velocity>=-.1&&wetTicks>=4;}
}

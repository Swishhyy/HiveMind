/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

/** Loaded-player contacts must not disappear from the scan budget in a crowded mob area. */
public final class AwarenessPolicy {
    private AwarenessPolicy(){}
    public static int range(int configured){return Math.max(64,Math.min(2048,configured));}
    public static boolean within(double distanceSquared,int configured){return Double.isFinite(distanceSquared)&&distanceSquared>=0&&distanceSquared<=(double)range(configured)*range(configured);}
    public static double priority(boolean player,boolean watched,double distanceSquared){
        if(!Double.isFinite(distanceSquared)||distanceSquared<0)return Double.POSITIVE_INFINITY;
        return (watched?-1e12:player?0:1e12)+distanceSquared;
    }
}

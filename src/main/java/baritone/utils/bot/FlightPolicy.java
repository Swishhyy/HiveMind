/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.bot;

import java.util.List;

/** Flight decisions use observed positions; they never invent an unloaded player's location. */
public final class FlightPolicy {
    public enum Mode { TRAVEL, ESCAPE, PURSUIT, SURVEY }
    public record Point(double x, double y, double z) {
        public double distanceSquared(Point other) {
            return Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2);
        }
    }
    private FlightPolicy() {}
    public static int reserve(Mode mode,int configured,int vanilla) {
        return mode==Mode.ESCAPE?2:Math.max(2,Math.max(configured,vanilla));
    }
    public static int durability(Mode mode,int vanilla) {
        return Math.max(mode==Mode.ESCAPE?10:32,vanilla+1);
    }
    public static boolean supplied(Mode mode,int durability,int rockets,int configuredReserve,int vanillaReserve,int vanillaDurability) {
        return durability>=durability(mode,vanillaDurability)&&rockets>=reserve(mode,configuredReserve,vanillaReserve)+2;
    }
    public static boolean shouldPursue(boolean hunting,boolean enabled,boolean flying,double horizontal,double vertical) {
        return hunting&&enabled&&flying&&(horizontal>24||Math.abs(vertical)>12);
    }
    public static boolean fresh(long now,long seen) { return seen>0&&now>=seen&&now-seen<=8000; }
    public static boolean replan(long now,long last,Point old,Point next) {
        return old==null||now-last>=500&&old.distanceSquared(next)>=16*16;
    }
    public static Point intercept(Point target,Point velocity) {
        // A short, bounded lead reduces tail chasing without projecting a target across unloaded terrain.
        double length=Math.sqrt(velocity.distanceSquared(new Point(0,0,0)));
        double scale=length>0?Math.min(12,48/length):0;
        return new Point(target.x+velocity.x*scale,target.y+velocity.y*scale,target.z+velocity.z*scale);
    }
    public static Point escape(Point from,List<Point> threats,int configured,double heading) {
        int distance=Math.max(512,Math.min(8192,configured));
        double dx=0,dz=0;
        for(Point threat:threats) {
            double x=from.x-threat.x,z=from.z-threat.z,weight=1/Math.max(1,x*x+z*z);
            dx+=x*weight;dz+=z*weight;
        }
        double angle=Math.hypot(dx,dz)<.001?heading:Math.atan2(dz,dx);
        return new Point(from.x+Math.cos(angle)*distance,from.y,from.z+Math.sin(angle)*distance);
    }
}

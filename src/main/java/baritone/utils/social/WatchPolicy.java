/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.social;

/** Stable watching and paced retries, independent of world access. */
public final class WatchPolicy {
    private WatchPolicy() {}

    public static boolean inRange(double horizontal, double vertical, int requested, int tolerance, int height, boolean settled) {
        int distance = SocialPolicy.stalkDistance(requested);
        int slack = StealthPolicy.bounded(tolerance,4,32);
        if (!settled) slack = Math.max(4,slack/2);
        return Double.isFinite(horizontal) && horizontal >= Math.max(12,distance-slack) && horizontal <= distance+slack
                && Double.isFinite(vertical) && Math.abs(vertical) <= Math.min(8,StealthPolicy.bounded(height,2,8)+(settled?2:0));
    }

    /** Turning heads never invalidate a station; sustained observation is handled by scatter. */
    public static final class Station {
        private boolean settled;
        private int blindTicks, rangeTicks;
        public boolean settled() { return settled; }
        public void settle() { settled=true;blindTicks=rangeTicks=0; }
        public void reset() { settled=false;blindTicks=rangeTicks=0; }
        public boolean hold(boolean visible, boolean withinRange, int lostSightLimit) {
            if (!settled) return false;
            blindTicks=visible?0:Math.min(600,blindTicks+1);
            rangeTicks=withinRange?0:Math.min(40,rangeTicks+1);
            if (blindTicks>=StealthPolicy.bounded(lostSightLimit,20,600) || rangeTicks>=40) {
                reset();return false;
            }
            return true;
        }
    }

    /** Partial body cover and a nearby backdrop matter, but a small improvement cannot justify a long orbit. */
    public static double vantageScore(double travelSquared, double facingDot, double cover, double vertical, double rangeError) {
        if (!Double.isFinite(travelSquared) || !Double.isFinite(facingDot) || !Double.isFinite(cover)
                || !Double.isFinite(vertical) || !Double.isFinite(rangeError)) return Double.POSITIVE_INFINITY;
        return Math.max(0,travelSquared)*0.12 + Math.max(0,facingDot+0.15)*240
                + (1-Math.max(0,Math.min(1,cover)))*180 + Math.abs(vertical)*4 + Math.abs(rangeError)*2;
    }

    public static long retryDelay(int failures, long requested) {
        long base=StealthPolicy.bounded(requested,1000L,30000L);
        return Math.min(30000L,base*(1L<<StealthPolicy.bounded(failures-1,0,3)));
    }
}

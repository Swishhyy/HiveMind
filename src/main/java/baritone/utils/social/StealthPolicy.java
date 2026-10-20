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

import java.util.function.BooleanSupplier;

/** Geometry and bounded decisions shared by stalking and deterministic regression checks. */
public final class StealthPolicy {
    private StealthPolicy() {}
    public static int bounded(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
    public static long bounded(long value, long minimum, long maximum) { return Math.max(minimum, Math.min(maximum, value)); }

    public static double gazeDot(double lx, double ly, double lz, double dx, double dy, double dz) {
        double divisor = Math.sqrt(lx*lx+ly*ly+lz*lz) * Math.sqrt(dx*dx+dy*dy+dz*dz);
        return divisor > 0 && Double.isFinite(divisor) ? Math.max(-1,Math.min(1,(lx*dx+ly*dy+lz*dz)/divisor)) : -1;
    }
    public static boolean looking(double dot, boolean visible, int halfAngle) {
        return visible && Double.isFinite(dot) && dot >= Math.cos(Math.toRadians(bounded(halfAngle,5,60)));
    }
    public static int gazeTicks(int previous, boolean watched, int threshold) {
        return watched ? Math.min(bounded(threshold,10,200),Math.max(0,previous)+1) : Math.max(0,previous-2);
    }
    public static boolean openHeight(double vertical, int maximum) { return Math.abs(vertical) <= bounded(maximum,2,8); }
    /** Vanilla renders an upright player's name tag through walls up to this distance; crouching hides it there. */
    public static final int NAME_TAG_RANGE = 64;
    /**
     * Crouching only matters where someone could perceive the bot: footsteps inside hearing range, or a name tag or
     * body inside that player's possible field of view. The sight-line check is lazy because it costs a ray trace.
     */
    public static boolean mayBeNoticed(double distance, double facingDot, boolean hidden, int hearingRange, int viewHalfAngle, BooleanSupplier sight) {
        if (!Double.isFinite(distance)) return false;
        if (distance <= bounded(hearingRange,4,48)) return true;
        if (hidden || !Double.isFinite(facingDot) || facingDot < Math.cos(Math.toRadians(bounded(viewHalfAngle,30,90)))) return false;
        return distance <= NAME_TAG_RANGE || sight.getAsBoolean();
    }
    public static boolean readyToAmbush(int quietTicks, int requiredQuiet, double facingDot, double maximumFacing, boolean visible,
                                        boolean alone, boolean equipped, float health, int hunger, int minimumHealth) {
        return quietTicks >= bounded(requiredQuiet,20,400) && facingDot < maximumFacing && visible && alone && equipped
                && health >= bounded(minimumHealth,13,20) && hunger > 6;
    }
    public static boolean mayStartCombat(float health, int hunger, int minimumHealth, boolean equipped) {
        return health >= bounded(minimumHealth,13,20) && hunger > 6 && equipped;
    }
    /** Once engaged, only low health (half the limit while a totem is held) or being outnumbered ends the fight. */
    public static boolean lowHealth(float health, float absorption, int retreatHealth, boolean totem) {
        int limit = bounded(retreatHealth,2,19);
        return health + Math.max(0,absorption) < (totem ? Math.max(2,limit/2) : limit);
    }
    public static boolean outnumbered(int nearbyOpponents, int retreatPlayers) { return nearbyOpponents >= bounded(retreatPlayers,2,8); }
    public static double clearancePenalty(double horizontalDistance,int minimum) {
        return Double.isFinite(horizontalDistance)&&horizontalDistance<minimum?10000+Math.max(0,minimum-horizontalDistance)*1000:0;
    }
    public static boolean frequentPlace(int visits, long observedSeconds) { return visits >= 3 && observedSeconds >= 180; }
    public static boolean shouldReport(long now, long last, double movedSquared, long interval) {
        return last == 0 || now-last >= bounded(interval,10000L,600000L) && movedSquared >= 64*64;
    }
}

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

/** Geometry and limits shared by client behavior and dependency-free safety checks. */
public final class SocialPolicy {
    private SocialPolicy() {}
    public static int stalkDistance(int value) { return Math.max(16, Math.min(128, value)); }
    public static int fleeDistance(int value, boolean warden) { return Math.max(warden ? 64 : 16, Math.min(128, value)); }
    public static int searchRadius(int value) { return Math.max(32, Math.min(512, value)); }
    public static double horizontal(double x, double z) { return Math.hypot(x, z); }
    public static boolean inBand(double x, double y, double z, int distance) {
        double radius = horizontal(x, z);
        return radius >= distance - 4 && radius <= distance + 4 && Math.abs(y) <= 8;
    }
    public static double bandPenalty(double x, double y, double z, int distance) {
        double radius = horizontal(x, z);
        return Math.max(0, distance - 4 - radius) + Math.max(0, radius - distance - 4) + Math.max(0, Math.abs(y) - 8);
    }
    public static boolean resumeAfterThreat(boolean separated, int quietTicks) { return separated && quietTicks >= 100; }
    public record Offset(int x, int z) {}
    public static Offset searchOffset(int step, int maximum) {
        int radius = Math.min(searchRadius(maximum), 64 * (1 + Math.max(0, step) / 8));
        double angle = Math.floorMod(step, 8) * Math.PI / 4;
        return new Offset((int) Math.round(Math.cos(angle) * radius), (int) Math.round(Math.sin(angle) * radius));
    }
    public static int searchSteps(int maximum) { return ((searchRadius(maximum) + 63) / 64) * 8; }
}

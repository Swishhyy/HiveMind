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

/** Stand-off movement must never confuse getting farther away with approaching the watched player. */
public final class SocialPolicyChecks {
    private SocialPolicyChecks() {}
    public static void main(String[] args) {
        runAll();
        System.out.println("Social policy checks passed: viewing bands, retreat limits, quiet periods, bounded search");
    }
    public static void runAll() {
        check(SocialPolicy.inBand(40, 0, 0, 40), "Forty blocks satisfies the viewing goal");
        check(SocialPolicy.inBand(0, 8, -36, 40), "Include the inner and vertical boundaries");
        check(SocialPolicy.inBand(-44, 0, 0, 40), "Include the outer boundary");
        check(!SocialPolicy.inBand(35, 0, 0, 40), "A player approaching the bot requires backing away");
        check(!SocialPolicy.inBand(45, 0, 0, 40), "A player leaving the bot requires following");
        check(!SocialPolicy.inBand(40, 9, 0, 40), "Horizontal separation alone cannot be used to watch from an arbitrary floor");
        for (int distance = 16; distance <= 128; distance++) {
            for (int offset = 0; offset <= 160; offset++) {
                double penalty = SocialPolicy.bandPenalty(offset, 0, 0, distance);
                check(Double.isFinite(penalty) && penalty >= 0, "Goal costs stay finite and nonnegative");
                check((penalty == 0) == SocialPolicy.inBand(offset, 0, 0, distance), "Only the observation ring has zero goal cost");
            }
        }
        check(SocialPolicy.bandPenalty(0, 0, 0, 40) > SocialPolicy.bandPenalty(30, 0, 0, 40), "A too-close bot is guided away from its target");
        check(SocialPolicy.bandPenalty(80, 0, 0, 40) > SocialPolicy.bandPenalty(50, 0, 0, 40), "A distant bot is guided toward the observation ring");
        check(Double.isFinite(SocialPolicy.bandPenalty(60000000, 2048, -60000000, 40)), "World-edge coordinates do not overflow");
        check(SocialPolicy.stalkDistance(Integer.MIN_VALUE) == 16 && SocialPolicy.stalkDistance(Integer.MAX_VALUE) == 128, "Bound custom stand-off distances");
        check(SocialPolicy.fleeDistance(1, false) == 16 && SocialPolicy.fleeDistance(32, true) == 64, "Retreat preserves extra separation from a warden");
        check(SocialPolicy.attackedDistance(32, 40) == 56, "A ranged attack beyond the current escape distance requires moving farther away");
        check(SocialPolicy.attackedDistance(64, 2) == 64, "An additional attack cannot shorten an existing retreat");
        check(SocialPolicy.attackedDistance(128, 60000000) == 128, "Repeated long-range attacks keep the retreat target bounded");
        check(!SocialPolicy.resumeAfterThreat(false, 1000), "Elapsed time cannot resume a task while a threat is still close");
        check(!SocialPolicy.resumeAfterThreat(true, 99) && SocialPolicy.resumeAfterThreat(true, 100), "Require the whole quiet period before resuming work");
        for (int radius : new int[]{1, 32, 64, 100, 256, 1000000}) {
            int bound = SocialPolicy.searchRadius(radius);
            int steps = SocialPolicy.searchSteps(radius);
            check(steps > 0 && steps <= 64, "Local searches have a finite number of waypoints");
            for (int step = 0; step < steps; step++) {
                var offset = SocialPolicy.searchOffset(step, radius);
                check(Math.hypot(offset.x(), offset.z()) <= bound + 1, "Search waypoints stay inside the configured local radius");
            }
        }
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}

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

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;

/** A ring rather than a point: arriving never requires walking into the observed player. */
public final class GoalStandoff implements Goal {
    private final BlockPos target;
    private final int distance;
    public GoalStandoff(BlockPos target, int distance) {
        this.target = target.immutable();
        this.distance = SocialPolicy.stalkDistance(distance);
    }
    public BlockPos target(){return target;}
    @Override public boolean isInGoal(int x, int y, int z) {
        return SocialPolicy.inBand((double) x - target.getX(), (double) y - target.getY(), (double) z - target.getZ(), distance);
    }
    @Override public double heuristic(int x, int y, int z) {
        return SocialPolicy.bandPenalty((double) x - target.getX(), (double) y - target.getY(), (double) z - target.getZ(), distance)
                * Baritone.settings().costHeuristic.value;
    }
    @Override public String toString() { return "Watch from about " + distance + " blocks"; }
}

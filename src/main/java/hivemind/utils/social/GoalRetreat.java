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

import hivemind.HiveMind;
import hivemind.api.pathing.goals.Goal;
import net.minecraft.core.BlockPos;
import java.util.List;

/** Immutable threat snapshot with constant-size goal heuristic, including wardens' larger separation. */
public final class GoalRetreat implements Goal {
    public record Threat(BlockPos position, int distance) {}
    private final List<Threat> threats;
    public GoalRetreat(List<Threat> threats) {
        if (threats.isEmpty()) throw new IllegalArgumentException("Retreat requires a threat");
        this.threats = List.copyOf(threats);
    }
    @Override public boolean isInGoal(int x, int y, int z) { return penalty(x, z) == 0; }
    private double penalty(int x, int z) {
        double worst = 0;
        for (Threat threat : threats) worst = Math.max(worst, threat.distance()
                - Math.hypot((double) x - threat.position().getX(), (double) z - threat.position().getZ()));
        return worst;
    }
    @Override public double heuristic(int x, int y, int z) { return penalty(x, z) * HiveMind.settings().costHeuristic.value; }
    @Override public String toString() { return "Retreat from " + threats.size() + " threats"; }
}

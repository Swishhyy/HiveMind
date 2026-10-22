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

package hivemind.command.defaults;

import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.CommandException;
import hivemind.api.pathing.goals.Goal;
import hivemind.api.pathing.goals.GoalAxis;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class AxisCommand extends Command {

    public AxisCommand(IHiveMind hivemind) {
        super(hivemind, "axis", "highway");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        Goal goal = new GoalAxis();
        hivemind.getCustomGoalProcess().setGoal(goal);
        logDirect(String.format("Goal: %s", goal.toString()));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set a goal to the axes";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The axis command sets a goal that tells HiveMind to head towards the nearest axis. That is, X=0 or Z=0.",
                "",
                "Usage:",
                "> axis"
        );
    }
}

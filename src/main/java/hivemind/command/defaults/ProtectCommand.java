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

import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import hivemind.utils.mining.MiningWaypoints;

import java.util.List;
import java.util.stream.Stream;

public final class ProtectCommand extends Command {
    public ProtectCommand(IHiveMind hivemind) {
        super(hivemind, "protect");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (args.hasAny() && args.peekString().equalsIgnoreCase("clear")) {
            args.getString();
            MiningWaypoints.clearProtection(ctx.worldData());
            logDirect("Manual protected areas cleared in this dimension; the deposit chest remains protected");
        } else if (args.hasAny() && args.peekString().equalsIgnoreCase("list")) {
            args.getString();
            logDirect("Protected areas (including storage chests): " + MiningWaypoints.protection(ctx));
            return;
        } else {
            int radius = args.hasAny() ? args.getAs(Integer.class) : 16;
            if (radius < 1 || radius > 256) {
                throw new CommandInvalidStateException("Choose a protection radius between 1 and 256 blocks");
            }
            MiningWaypoints.protect(ctx.worldData(), ctx.playerFeet(), radius);
            logDirect("Protected a cube centered at " + ctx.playerFeet() + " with radius " + radius + "; walking through is allowed");
        }
        ((HiveMind) hivemind).getPathingBehavior().softCancelIfSafe();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("16", "32", "list", "clear")
                .filterPrefix(args.getString()).stream() : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Protect a base from automated block breaking and placement";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of("protect [radius] - Mark an area around your current position (default radius 16).",
                "protect list - List protected areas in this world and dimension.",
                "protect clear - Remove manual protection markers in this world and dimension.",
                "Protection persists in HiveMind's waypoint storage and still allows walking through existing openings.",
                "Use this for bases built from natural materials that cannot be identified reliably by the client.");
    }
}

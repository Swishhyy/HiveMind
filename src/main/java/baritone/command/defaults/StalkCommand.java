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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.process.StalkProcess;
import java.util.List;
import java.util.stream.Stream;

public final class StalkCommand extends Command {
    public StalkCommand(IBaritone baritone) { super(baritone, "stalk"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        String target = args.hasAny() ? args.getString() : "random";
        StalkProcess process = ((Baritone) baritone).getStalkProcess();
        if (target.equalsIgnoreCase("stop")) {
            process.onLostControl();
            baritone.getPathingBehavior().softCancelIfSafe();
            logDirect("Stalking stopped");
        } else if (target.equalsIgnoreCase("status")) logDirect(process.status());
        else try { process.start(target); }
        catch (IllegalStateException error) { throw new CommandInvalidStateException(error.getMessage()); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAtMostOne() || ctx.minecraft().getConnection() == null) return Stream.empty();
        return new TabCompleteHelper().append("random", "status", "stop")
                .append(ctx.minecraft().getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().name()))
                .filterPrefix(args.getString()).stream();
    }
    @Override public String getShortDesc() { return "Follow a player from about 40 blocks and crouch while watching"; }
    @Override public List<String> getLongDesc() {
        return List.of("stalk <player> - Watch that online player from stalkDistance (default 40) blocks.",
                "stalk [random] - Pick one player, preferring someone currently visible to the client.",
                "stalk status / stop - Show the target or stop watching.",
                "Crouches and faces the player while stopped; moves normally when repositioning.",
                "Checks recent last-seen positions and searches locally when the target is outside view.",
                "A client cannot know arbitrary player coordinates elsewhere on the server.",
                "Conflict retreat takes priority. Pause/stop, death, disconnect, world changes, and the search limit cancel or pause watching.");
    }
}

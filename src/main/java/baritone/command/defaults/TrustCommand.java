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
import java.util.*;
import java.util.stream.Stream;

public final class TrustCommand extends Command {
    public TrustCommand(IBaritone bot) { super(bot, "trust"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "list";
        var config = ((Baritone) baritone).getBotControl().config();
        try {
            if (action.equals("list")) { args.requireMax(0); logDirect("Trusted controller UUIDs: " + config.players()); return; }
            if (action.equals("reload")) { args.requireMax(0); config.reload(); config.players(); logDirect("Reloaded bot-control.properties"); return; }
            if (!action.equals("add") && !action.equals("remove")) throw new IllegalArgumentException("Use trust add/remove <online player or UUID>, list, or reload");
            args.requireMin(1); String input = args.getString(); args.requireMax(0);
            UUID id;
            try { id = UUID.fromString(input); }
            catch (IllegalArgumentException e) {
                if (ctx.minecraft().getConnection() == null) throw new IllegalStateException("Join the server before resolving an online player");
                id = ctx.minecraft().getConnection().getOnlinePlayers().stream().filter(info -> info.getProfile().name().equalsIgnoreCase(input))
                        .map(info -> info.getProfile().id()).findFirst().orElseThrow(() -> new IllegalArgumentException("That player is not online; use an exact UUID instead"));
            }
            config.change(id, action.equals("add")); logDirect((action.equals("add") ? "Trusted " : "Removed ") + id + " for this server/save");
        } catch (IllegalArgumentException | IllegalStateException e) { throw new CommandInvalidStateException(e.getMessage()); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasAtMostOne()) return new TabCompleteHelper().append("add", "remove", "list", "reload").filterPrefix(args.getString()).stream();
        args.getString(); return ctx.minecraft().getConnection() == null ? Stream.empty() : new TabCompleteHelper()
                .append(ctx.minecraft().getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().name())).filterPrefix(args.getString()).stream();
    }
    @Override public String getShortDesc() { return "Allow specific UUIDs to control the bot through whispers"; }
    @Override public List<String> getLongDesc() { return List.of("trust add/remove <online player or UUID>; trust list; trust reload.",
            "Only authenticated incoming private chat is accepted by default; public chat never controls the bot.",
            "Whisper #mine 32 netherite, #job status, #pause, #resume, #stop, #deposit now, or #stalk Player.",
            "Trust and storage configuration cannot be changed remotely. See MINING.md for server-specific whisper formats."); }
}

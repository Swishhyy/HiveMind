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
import baritone.api.utils.SettingsUtil;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.*;
import java.util.stream.Stream;

public final class SurvivalCommand extends Command {
    public SurvivalCommand(IBaritone bot) { super(bot, "survival", "defense"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status"; args.requireMax(0);
        Baritone bot = (Baritone) baritone;
        if (label.equals("defense")) {
            if (Set.of("flee", "defend").contains(action)) { Baritone.settings().botConflictMode.value = action; SettingsUtil.save(Baritone.settings()); }
            else if (!action.equals("status")) throw new CommandInvalidStateException("Use defense flee, defend, or status");
            logDirect("Conflict mode: " + Baritone.settings().botConflictMode.value + "; monitoring " + Baritone.settings().fleeConflicts.value);
        } else if (action.equals("retry")) { bot.getSurvivalProcess().retry(); logDirect("Survival checks will retry; use job resume too if its queue is paused"); }
        else if (action.equals("status")) logDirect(bot.getSurvivalProcess().status());
        else throw new CommandInvalidStateException("Use survival status or retry");
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return new TabCompleteHelper().append(label.equals("defense") ? new String[]{"status", "flee", "defend"} : new String[]{"status", "retry"}).filterPrefix(args.getString()).stream();
    }
    @Override public String getShortDesc() { return "Inspect survival pauses or choose flee/defend conflict behavior"; }
    @Override public List<String> getLongDesc() { return List.of("survival status/retry - Inspect food, health, durability, and supply pauses.",
            "defense flee/defend - Default flee; defend only retaliates against a confirmed nearby attacker when healthy.",
            "Defend uses a hotbar sword/axe and an offhand shield if available. It never pursues players, creepers or Wardens.",
            "Low health, multiple threats, missing weapons, sculk, and attack timeouts use retreat."); }
}

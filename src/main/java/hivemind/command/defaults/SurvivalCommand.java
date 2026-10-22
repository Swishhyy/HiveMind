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
import hivemind.api.utils.SettingsUtil;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import java.util.*;
import java.util.stream.Stream;

public final class SurvivalCommand extends Command {
    public SurvivalCommand(IHiveMind bot) { super(bot, "survival", "defense"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status"; args.requireMax(0);
        HiveMind bot = (HiveMind) hivemind;
        if (label.equals("defense")) {
            if (Set.of("flee", "defend").contains(action)) { HiveMind.settings().botConflictMode.value = action; SettingsUtil.save(HiveMind.settings()); }
            else if (!action.equals("status")) throw new CommandInvalidStateException("Use defense flee, defend, or status");
            logDirect("Conflict mode: " + HiveMind.settings().botConflictMode.value + "; monitoring " + HiveMind.settings().fleeConflicts.value);
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

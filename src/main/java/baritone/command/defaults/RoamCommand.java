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
import baritone.api.command.exception.*;
import java.util.*;
import java.util.stream.Stream;

public final class RoamCommand extends Command {
    public RoamCommand(IBaritone bot){super(bot,"roam");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        String mode=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";args.requireMax(0);var bot=(Baritone)baritone;
        if(mode.equals("on")){Baritone.settings().botAutoRoam.value=true;bot.getRoamProcess().hold(false);bot.getSurvivalProcess().retry();}
        else if(mode.equals("off")){bot.getRoamProcess().hold(true);bot.getPathingBehavior().softCancelIfSafe();}
        else if(!mode.equals("status"))throw new CommandInvalidStateException("Use roam on/off/status");
        logDirect(bot.getRoamProcess().status());
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("on","off","status");}
    @Override public String getShortDesc(){return "Automatically scout and map terrain between tasks";}
    @Override public List<String> getLongDesc(){return List.of("roam on/off/status","Idle roaming is on by default. Stop parks it for this server; roam on or resume releases it.","Foreground work, care, conflict, containers and manual pause take priority. Roaming never breaks or places blocks.");}
}

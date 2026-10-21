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
import baritone.utils.mining.MiningRouteAccess;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.*;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.stream.Stream;

public final class MapMarkerCommand extends Command {
    public MapMarkerCommand(IBaritone bot){super(bot,"mark","mapmark");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        Baritone bot=(Baritone)baritone;UUID owner=bot.getJobManager().requestOwner();
        try{
            String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"list";
            if(action.equals("list")){
                args.requireMax(0);var markers=bot.getNetwork().markers();say(bot,owner,markers.size()+" map marker(s)");
                for(var m:markers.stream().limit(12).toList())say(bot,owner,m.id()+" "+m.label()+" ["+m.kind()+"] "+m.point()+" · "+m.reporterName());
            }else if(action.equals("remove")){
                UUID id=UUID.fromString(args.getString());args.requireMax(0);bot.getNetwork().removeMarker(id);say(bot,owner,"Removed map marker "+id);
            }else if(action.equals("add")||action.equals("danger")){
                String name=args.getString();if(!name.matches("[A-Za-z0-9_-]{1,40}"))throw new IllegalArgumentException("Use a 1-40 character marker name with letters, numbers, _ or -");
                BlockPos point=args.hasAny()?args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet()):ctx.playerFeet();args.requireMax(0);
                var marker=bot.getNetwork().addMarker(action.equals("danger")?"danger":"waypoint",name,"Set by a controller",MiningRouteAccess.endpoint(ctx,point),null,System.currentTimeMillis());say(bot,owner,"Map marker "+name+": "+marker.id());
            }else throw new IllegalArgumentException("Use mark add/danger <name> [x y z], mark list, or mark remove <id>");
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    private void say(Baritone bot,UUID owner,String text){logDirect(text);if(owner!=null)bot.getBotControl().reply(owner,text);}
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("add","danger","list","remove");}
    @Override public String getShortDesc(){return "Add, list and remove shared map waypoints and danger marks";}
    @Override public List<String> getLongDesc(){return List.of("mark add <name> [x y z] - Save a named waypoint in the current dimension.","mark danger <name> [x y z] - Save a danger mark.","mark list; mark remove <UUID> - Remove on the bot that created the mark.","Logout and death markers are recorded automatically. All marks appear on the local network map.");}
}

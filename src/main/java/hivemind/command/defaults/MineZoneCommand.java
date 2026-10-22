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
import hivemind.api.command.datatypes.RelativeBlockPos;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import hivemind.utils.bot.MiningZone;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.stream.Stream;

public final class MineZoneCommand extends Command {
    public MineZoneCommand(IHiveMind bot){super(bot,"minezone");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        if(!action.equals("status")&&((HiveMind)hivemind).getJobManager().requestOwner()!=null)throw new CommandInvalidStateException("Mining boundary edits must be made locally on the bot account");
        var locations=MiningRouteAccess.locations(ctx);String dimension=MiningRouteAccess.dimension(ctx);
        try{
            switch(action){
                case "pos1","pos2"->{
                    BlockPos pos=args.hasAny()?args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet()):ctx.playerFeet();args.requireMax(0);
                    locations.setCorner(dimension,action.equals("pos1")?1:2,MiningRouteAccess.endpoint(ctx,pos));
                    var a=locations.corner(dimension,1);var b=locations.corner(dimension,2);
                    if(a!=null&&b!=null)locations.setZone(dimension,MiningZone.between(dimension,a.x(),a.y(),a.z(),b.x(),b.y(),b.z()));
                    logDirect("Mining "+action+" saved at "+pos);
                }
                case "set"->{args.requireMin(6);BlockPos a=args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet());BlockPos b=args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet());args.requireMax(0);locations.setZone(dimension,MiningZone.between(dimension,a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ()));}
                case "on","off"->{args.requireMax(0);locations.enableZones(action.equals("on"));}
                case "clear"->{args.requireMax(0);locations.setZone(dimension,null);locations.setCorner(dimension,1,null);locations.setCorner(dimension,2,null);}
                case "status"->{args.requireMax(0);logDirect("Mining boundaries "+(locations.zonesEnabled()?"enabled":"disabled")+": "+locations.zone(dimension));return;}
                default->throw new IllegalArgumentException("Use minezone pos1/pos2 [x y z], set <two corners>, on, off, clear, or status");
            }
            ((HiveMind)hivemind).getMineProcess().invalidateWorldTargets();((HiveMind)hivemind).getPathingBehavior().softCancelIfSafe();
            logDirect("Mining boundaries "+(locations.zonesEnabled()?"enabled":"disabled")+": "+locations.zone(dimension));
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args)throws CommandException{return args.hasAtMostOne()?new TabCompleteHelper().append("pos1","pos2","set","on","off","clear","status").filterPrefix(args.getString()).stream():Stream.empty();}
    @Override public String getShortDesc(){return "Restrict mining and tunnel breaking to selected boundaries";}
    @Override public List<String> getLongDesc(){return List.of("minezone pos1 [x y z]; minezone pos2 [x y z] - Save inclusive corners in this dimension.","minezone set x1 y1 z1 x2 y2 z2; minezone status/on/off/clear.","Walking and storage trips may leave the area; breaking while mining stays inside it.","With boundaries enabled, every mining dimension requires its own saved zone.");}
}

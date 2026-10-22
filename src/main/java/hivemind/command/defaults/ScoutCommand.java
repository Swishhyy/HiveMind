/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.MapOrder;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.stream.Stream;
public final class ScoutCommand extends Command {
    public ScoutCommand(IHiveMind bot){super(bot,"scout");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        HiveMind bot=(HiveMind)hivemind;String kind=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        if(kind.equals("status")){args.requireMax(0);logDirect(bot.getScoutProcess().status());return;}
        if(kind.equals("stop")){args.requireMax(0);bot.getFlight().cancel();bot.getScoutProcess().onLostControl();bot.getPathingBehavior().softCancelIfSafe();logDirect("Map order stopped");return;}
        if(bot.isPlayerClient()||ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("An online bot client is required");
        args.requireMin(3);String dimension=args.getString();int x=args.getAs(Integer.class),z=args.getAs(Integer.class),radius=kind.equals("explore")?args.getAs(Integer.class):0;args.requireMax(0);
        MapOrder order;try{order=new MapOrder(kind,dimension,x,z,radius);}catch(IllegalArgumentException e){throw new CommandInvalidStateException(e.getMessage());}
        if(!dimension.equals(MiningRouteAccess.dimension(ctx)))throw new CommandInvalidStateException("Bot is in a different dimension; map order rejected");
        if(!ctx.world().getWorldBorder().isWithinBounds(new BlockPos(x,64,z)))throw new CommandInvalidStateException("Destination is outside the world border");
        bot.getJobManager().pauseJobs();bot.getAutonomy().replaceWork();bot.getAutonomy().pause(false);bot.getPathingBehavior().cancelEverything();bot.getScoutProcess().start(order);logDirect(bot.getScoutProcess().status());
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("go","explore","status","stop");}
    @Override public String getShortDesc(){return "Go to a map point or survey a bounded area";}
    @Override public List<String> getLongDesc(){return List.of("scout go <dimension> <x> <z>","scout explore <dimension> <x> <z> <radius>","scout status/stop","Right-click the atlas to create these orders. Radius: 32–2048 blocks. Bots must already be in the map dimension. Surveys use elytra when equipped, otherwise walk; existing queued work is paused.");}
}

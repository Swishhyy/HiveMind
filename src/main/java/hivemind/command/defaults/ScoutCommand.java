/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.MapOrder;
import hivemind.utils.mining.WorldRoutes;
import hivemind.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.stream.Stream;
public final class ScoutCommand extends Command {
    public ScoutCommand(IHiveMind bot){super(bot,"scout");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        HiveMind bot=(HiveMind)hivemind;String kind=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        var queue=bot.getScoutProcess().queue();
        if(Set.of("list","pause","resume","clear","remove","move").contains(kind)){
            if(queue==null)throw new CommandInvalidStateException("Join a world first");
            if(kind.equals("list")){args.requireMax(0);for(var task:queue.tasks())logDirect("#"+task.id()+" "+task.kind()+" "+task.point()+" radius "+task.radius());return;}
            if(kind.equals("remove")||kind.equals("move")){long id=args.getAs(Long.class);int position=kind.equals("move")?args.getAs(Integer.class):0;args.requireMax(0);if(queue.first()!=null&&queue.first().id()==id){bot.getFlight().cancel();bot.getPathingBehavior().secretInternalSegmentCancel();}if(kind.equals("move"))queue.move(id,position);else queue.remove(id);bot.getScoutProcess().reset();}
            else {args.requireMax(0);bot.getFlight().cancel();bot.getPathingBehavior().secretInternalSegmentCancel();if(kind.equals("clear")){queue.clear();bot.getScoutProcess().reset();}else {if(kind.equals("resume")){bot.getJobManager().pauseJobs();bot.getAutonomy().replaceWork();bot.getPathingBehavior().cancelEverything();}bot.getScoutProcess().hold(kind.equals("pause"));}}
            logDirect(bot.getScoutProcess().status());return;
        }
        boolean append=kind.equals("add");if(append)kind=args.getString().toLowerCase(Locale.ROOT);
        if(kind.equals("return")){
            args.requireMax(0);var storage=MiningRouteAccess.deposit(ctx);if(storage==null)throw new CommandInvalidStateException("Register storage first");if(!append){bot.getJobManager().pauseJobs();bot.getAutonomy().replaceWork();bot.getPathingBehavior().cancelEverything();queue.clear();queue.pause(false);}bot.getScoutProcess().add("return",storage,0);logDirect(bot.getScoutProcess().status());return;
        }
        if(kind.equals("status")){args.requireMax(0);logDirect(bot.getScoutProcess().status());return;}
        if(kind.equals("stop")){args.requireMax(0);bot.getFlight().cancel();bot.getScoutProcess().hold(true);bot.getPathingBehavior().softCancelIfSafe();logDirect("Map order stopped");return;}
        if(bot.isPlayerClient()||ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("An online bot client is required");
        args.requireMin(3);String dimension=args.getString();int x=args.getAs(Integer.class),z=args.getAs(Integer.class),radius=kind.equals("explore")?args.getAs(Integer.class):0;args.requireMax(0);
        MapOrder order;try{order=new MapOrder(kind.equals("check")?"go":kind,dimension,x,z,radius);}catch(IllegalArgumentException e){throw new CommandInvalidStateException(e.getMessage());}
        if(!dimension.equals(MiningRouteAccess.dimension(ctx)))throw new CommandInvalidStateException("Bot is in a different dimension; map order rejected");
        if(!ctx.world().getWorldBorder().isWithinBounds(new BlockPos(x,64,z)))throw new CommandInvalidStateException("Destination is outside the world border");
        if(!append){bot.getJobManager().pauseJobs();bot.getAutonomy().replaceWork();bot.getPathingBehavior().cancelEverything();queue.clear();queue.pause(false);}
        bot.getScoutProcess().add(kind,new WorldRoutes.Endpoint(dimension,x,64,z),radius);logDirect(bot.getScoutProcess().status());
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("go","explore","check","return","add","list","pause","resume","clear","remove","move","status","stop");}
    @Override public String getShortDesc(){return "Go to a map point or survey a bounded area";}
    @Override public List<String> getLongDesc(){return List.of("scout go <dimension> <x> <z>","scout explore <dimension> <x> <z> <radius>","scout check <dimension> <x> <z>; scout return", "scout add go/explore/check ...; scout add return", "scout list/pause/resume/clear; scout remove <id>; scout move <id> <position>","scout status/stop","Right-click the atlas to create these orders. Radius: 32–2048 blocks. Bots must already be in the map dimension. Surveys use elytra when equipped, otherwise walk; existing queued work is paused.");}
}

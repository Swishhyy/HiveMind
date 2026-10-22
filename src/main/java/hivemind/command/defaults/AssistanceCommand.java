/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.MapOrder;
import hivemind.utils.bot.BotWork;
import hivemind.utils.mining.*;
import java.util.*;
import java.util.stream.Stream;
public final class AssistanceCommand extends Command {
    public AssistanceCommand(IHiveMind bot){super(bot,"backup","meet","bring","assist");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        var bot=(HiveMind)hivemind;if(ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Join the server first");
        try{
            if(!label.equals("assist")){String item="",quantity="";if(label.equals("bring")){item=args.getString();if(item.equals("rockets"))item="minecraft:firework_rocket";quantity=""+(args.hasAny()?args.getAs(Integer.class):32);if(Integer.parseInt(quantity)<1||Integer.parseInt(quantity)>4096)throw new IllegalArgumentException("Choose 1–4096 items");}args.requireMax(0);bot.getNetwork().requestAssistance(label,item,quantity);logDirect("Requested "+label+" from the nearest available bot");return;}
            if(bot.isPlayerClient())throw new IllegalStateException("Player clients cannot accept assistance work");
            if(BotWork.foreground(bot)||bot.getJobManager().hasQueuedJobs()||bot.getAutonomy().paused()||bot.getAutonomy().order()!=null)throw new IllegalStateException("Bot became busy before assistance arrived");
            String mode=args.getString(),name=args.getString(),dimension=args.getString();int x=args.getAs(Integer.class),y=args.getAs(Integer.class),z=args.getAs(Integer.class);var point=new WorldRoutes.Endpoint(dimension,x,y,z);
            if(!dimension.equals(MiningRouteAccess.dimension(ctx)))throw new IllegalStateException("Assistance target changed dimension");
            bot.getAutonomy().replaceWork();
            if(mode.equals("bring")){String item=args.getString();int quantity=args.getAs(Integer.class);args.requireMax(0);bot.getCompanionProcess().deliver(name,item,quantity,MiningRouteAccess.position(point));}
            else {args.requireMax(0);if(mode.equals("backup"))bot.getCompanionProcess().escortAt(name,point);else if(mode.equals("meet")){bot.getJobManager().pauseJobs();bot.getAutonomy().replaceWork();bot.getPathingBehavior().cancelEverything();bot.getScoutProcess().start(new MapOrder("go",dimension,x,z,0));}else throw new IllegalArgumentException("Unknown assistance mode");}
            logDirect("Assisting "+name+": "+mode);
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return label.equals("bring")?Stream.of("rockets","cooked_beef","totem_of_undying"):Stream.empty();}
    @Override public String getShortDesc(){return "Ask an idle network bot to meet, protect or bring supplies";}
    @Override public List<String> getLongDesc(){return List.of("backup — summon an available bot to your shared position and escort you","meet — summon an available bot to your current position","bring <item> [quantity] — fetch and deliver supplies; rockets is shorthand for firework_rocket","Works in player mode. Only healthy idle bots in your dimension are selected. Deliveries retain the existing handoff confirmation.");}
}

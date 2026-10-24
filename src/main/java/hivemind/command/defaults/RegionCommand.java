/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.utils.bot.BotBaseAccess;
import hivemind.utils.network.BotBaseStore;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.reliability.RegionRules;
import java.util.*;
import java.util.stream.Stream;

public final class RegionCommand extends Command {
    public RegionCommand(IHiveMind bot){super(bot,"region");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        if(ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Join a world first");
        var regions=((HiveMind)hivemind).getReliability().regions();String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"list";
        try{
            if(action.equals("list")){args.requireMax(0);for(var r:regions.regions())logDirect(r.name()+" "+r.kind().name().toLowerCase(Locale.ROOT)+" "+r.center()+" radius "+r.radius());return;}
            if(action.equals("remove")){String name=args.getString();args.requireMax(0);var removed=regions.regions().stream().filter(r->r.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown region"));regions.remove(name);if(removed.kind()==RegionRules.Kind.STORAGE)BotBaseAccess.store(ctx).named(removed.name(),null);logDirect("Region removed: "+name);return;}
            if(!action.equals("add"))throw new IllegalArgumentException("Use region add/list/remove");
            String name=args.getString();var kind=RegionRules.Kind.valueOf(args.getString().toUpperCase(Locale.ROOT));int x=args.getAs(Integer.class),z=args.getAs(Integer.class),radius=args.getAs(Integer.class);String dimension=args.hasAny()?args.getString():MiningRouteAccess.dimension(ctx);args.requireMax(0);
            var point=new Endpoint(dimension,x,ctx.playerFeet().getY(),z);regions.put(new RegionRules.Region(name,kind,point,radius));
            if(kind==RegionRules.Kind.STORAGE)BotBaseAccess.store(ctx).named(name,new BotBaseStore.Base(point,Math.min(32,radius)));logDirect("Saved "+kind.name().toLowerCase(Locale.ROOT)+" region "+name);
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("add","list","remove");}
    @Override public String getShortDesc(){return "Mark protected, dangerous, exploration, building and storage regions";}
    @Override public List<String> getLongDesc(){return List.of("region add <name> <protect|danger|explore|build|storage> <x> <z> <radius> [dimension]","region list; region remove <name>","Protected regions prohibit automated terrain changes; danger regions penalize routes. Explore/build/storage regions guide their corresponding tasks.");}
}

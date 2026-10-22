/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.BotBaseAccess;
import hivemind.utils.mining.*;
import hivemind.utils.network.BotBaseStore;
import java.util.*;
import java.util.stream.Stream;
public final class BotBaseCommand extends Command {
    public BotBaseCommand(IHiveMind bot){super(bot,"botbase","bot");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        var bot=(HiveMind)hivemind;if(ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Join a world first");
        if(label.equals("bot")&&!args.getString().equalsIgnoreCase("base"))throw new CommandInvalidStateException("Use #bot base set/status/scan/resupply/clear");
        String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        try{
            var store=BotBaseAccess.store(ctx);
            if(action.equals("set")){
                if(bot.getDepositProcess().isActive()||bot.getShulkerProcess().isActive()||bot.getOperations().isActive())throw new IllegalStateException("Finish the active supply trip before changing the base");
                WorldRoutes.Endpoint point;int radius=16;
                if(args.hasAny()&&args.peekString().contains(":")){String dimension=args.getString();int x=args.getAs(Integer.class),y=args.getAs(Integer.class),z=args.getAs(Integer.class);point=new WorldRoutes.Endpoint(dimension,x,y,z);if(args.hasAny())radius=args.getAs(Integer.class);}
                else {point=MiningRouteAccess.endpoint(ctx,ctx.playerFeet());if(args.hasAny())radius=args.getAs(Integer.class);}args.requireMax(0);
                store.set(new BotBaseStore.Base(point,radius));int chests=BotBaseAccess.scan(ctx);logDirect("Bot base saved: "+point+" · radius "+radius+" · "+chests+" loaded supply containers found");if(chests==0)logDirect("Containers will be discovered when this bot reaches the base; only loaded containers are scanned.");return;
            }
            args.requireMax(0);
            switch(action){
                case "status"->logDirect("Bot base: "+store.get()+" · "+bot.getShulkerProcess().reserveStatus());
                case "scan"->logDirect("Bot base scan found "+BotBaseAccess.scan(ctx)+" loaded supply containers");
                case "clear"->{if(bot.getOperations().isActive()||bot.getDepositProcess().isActive())throw new IllegalStateException("Finish the supply trip first");store.set(null);logDirect("Bot base cleared. Registered storage remains; remove it with #deposit remove if needed.");}
                case "resupply"->{if(bot.isPlayerClient())throw new IllegalStateException("Player clients remain under manual control");bot.getOperations().requestRestock();logDirect("Resupply requested; the current task is preserved");}
                default->throw new IllegalArgumentException("Use #botbase set [radius], set <dimension> <x> <y> <z> [radius], status, scan, resupply or clear");
            }
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("set","status","scan","resupply","clear");}
    @Override public String getShortDesc(){return "Register a nearby-chest resupply base and manage expedition reserves";}
    @Override public List<String> getLongDesc(){return List.of("botbase set [radius] — register this location and discover loaded supported containers within 16 blocks by default (4–32)","botbase set minecraft:overworld 100 64 -200 16 — explicit coordinates, usable from the controller for multiple bots","botbase status/scan/resupply/clear; #bot base is an alias","Existing container rules are preserved. Newly discovered storage is labeled as base supplies. Clearing the base does not delete registered storage.","Reserves: 128–256 rockets, 64–128 food, two usable elytras including equipped wings, three to four totems including offhand. Configure Loadout in controller settings or use #set.");}
}

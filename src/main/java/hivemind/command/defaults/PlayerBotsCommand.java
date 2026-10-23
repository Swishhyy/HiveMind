/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.network.PlayerBotOrders;
import hivemind.utils.mining.*;
import hivemind.process.DepositProcess;
import java.util.*;
import java.util.stream.Stream;
/** Configures remote bot accounts without running any automation on the issuing player. */
public final class PlayerBotsCommand extends Command {
    public PlayerBotsCommand(IHiveMind bot){super(bot,"bots");}
    private WorldRoutes.Endpoint point(IArgConsumer args,boolean chest)throws CommandException{
        if(args.hasAny()&&args.peekString().contains(":"))return new WorldRoutes.Endpoint(args.getString(),args.getAs(Integer.class),args.getAs(Integer.class),args.getAs(Integer.class));
        return MiningRouteAccess.endpoint(ctx,chest?ctx.getSelectedBlock().orElseThrow(()->new CommandInvalidStateException("Look at a storage chest/barrel, or supply <dimension> <x> <y> <z>")):ctx.playerFeet());
    }
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        if(ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Join the server first");var bot=(HiveMind)hivemind;
        try{
            if(!args.hasAny()){logDirect(bot.getNetwork().botCommandList());return;}
            String selector=args.getString();if(selector.equalsIgnoreCase("list")){args.requireMax(0);logDirect(bot.getNetwork().botCommandList());return;}
            String category=args.getString().toLowerCase(Locale.ROOT),action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status",command;
            if(category.equals("base")){
                if(action.equals("set")){var p=point(args,false);int radius=args.hasAny()?args.getAs(Integer.class):16;command=PlayerBotOrders.base(p,radius);}
                else{if(!Set.of("status","scan","clear","resupply").contains(action))throw new IllegalArgumentException("Use base set/status/scan/clear/resupply");command="botbase "+action;}
            }else if(category.equals("chest")||category.equals("storage")){
                if(action.equals("list")||action.equals("status")){int page=args.hasAny()?args.getAs(Integer.class):1;if(page<1||page>1000)throw new IllegalArgumentException("Choose a positive storage page");command="deposit point list "+page;}
                else{var p=point(args,true);if(action.equals("add")&&p.dimension().equals(MiningRouteAccess.dimension(ctx))&&ctx.world().hasChunkAt(MiningRouteAccess.position(p))&&!DepositProcess.supports(ctx.world().getBlockState(MiningRouteAccess.position(p)).getBlock()))throw new IllegalArgumentException("Choose a supported chest, copper chest, ender chest or barrel");String rule=args.hasAny()?args.getString():null;command=PlayerBotOrders.chest(action,p,rule);}
            }else throw new IllegalArgumentException("Use #bots <all|bot-name|UUID> base ... or chest ...");
            args.requireMax(0);logDirect(bot.getNetwork().requestBotCommand(selector,command));
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("list","all");}
    @Override public String getShortDesc(){return "Assign bot bases and storage using your own position or selected chest";}
    @Override public List<String> getLongDesc(){return List.of("bots list — list fresh connected autonomous bot accounts","bots all base set [radius] — use YOUR current position; radius defaults to 16","bots <name|UUID|all> base set <dimension> <x> <y> <z> [radius]","bots <target> base status/scan/clear/resupply","bots <target> chest add [any|ores|building|junk|supplies|disabled] — use YOUR selected chest/barrel","bots <target> chest remove — remove YOUR selected location from each bot, regardless of its storage ID","bots <target> chest add/remove <dimension> <x> <y> <z> [rule] — explicit location","bots <target> chest list [page] — show each bot's registered storage in your chat","Connect to the same authenticated network first. Player clients cannot be selected. Commands are not queued for offline bots; bot receipts appear in your chat.");}
}

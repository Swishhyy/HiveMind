/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.StorageRule;
import hivemind.utils.network.PlayerBotOrders;
import hivemind.utils.mining.*;
import hivemind.process.DepositProcess;
import java.util.*;
import java.util.stream.Stream;
/** Receiver-side endpoint edits keep storage IDs local to each bot and revalidate loaded containers. */
public final class StoragePointCommand extends Command {
    private String result="";
    public StoragePointCommand(IHiveMind bot){super(bot,"storagepoint");}
    public String result(){return result;}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        var bot=(HiveMind)hivemind;result="";
        try{
            if(bot.isPlayerClient()||ctx.player()==null||ctx.world()==null)throw new IllegalStateException("An online autonomous bot is required");
            String action=args.getString().toLowerCase(Locale.ROOT);MiningRouteAccess.deposits(ctx);var routes=MiningRouteAccess.store(ctx);
            if(action.equals("list")){
                int page=args.hasAny()?args.getAs(Integer.class):1;args.requireMax(0);var all=routes.deposits();int pages=Math.max(1,(all.size()+3)/4);if(page<1||page>pages)throw new IllegalArgumentException("Choose storage page 1–"+pages);
                var rows=all.stream().skip((page-1)*4L).limit(4).map(s->"#"+s.id()+" ["+s.rule().kind().name().toLowerCase(Locale.ROOT)+"] "+s.endpoint().dimension()+" "+s.endpoint().x()+" "+s.endpoint().y()+" "+s.endpoint().z()).toList();result="Storage "+page+"/"+pages+": "+(rows.isEmpty()?"none":String.join("; ",rows));logDirect(result);return;
            }
            if(!Set.of("add","remove").contains(action))throw new IllegalArgumentException("Use storagepoint add/remove/list");
            var point=new WorldRoutes.Endpoint(args.getString(),args.getAs(Integer.class),args.getAs(Integer.class),args.getAs(Integer.class));String rule=args.hasAny()?args.getString():null;args.requireMax(0);
            PlayerBotOrders.chest(action,point,rule);
            if(bot.getDepositProcess().isActive()||bot.getShulkerProcess().isActive()||bot.getOperations().isActive())throw new IllegalStateException("Finish the active supply trip before changing storage");
            boolean loaded=point.dimension().equals(MiningRouteAccess.dimension(ctx))&&ctx.world().hasChunkAt(MiningRouteAccess.position(point));
            if(action.equals("add")){
                if(loaded&&!DepositProcess.supports(ctx.world().getBlockState(MiningRouteAccess.position(point)).getBlock()))throw new IllegalArgumentException("The loaded location is not a supported storage container");
                var existing=routes.deposits().stream().filter(s->s.endpoint().equals(point)).findFirst().orElse(null);if(existing==null&&routes.deposits().size()>=64)throw new IllegalStateException("At most 64 registered containers");
                var saved=routes.addDeposit(point);
                if(rule!=null){var kind=StorageRule.parse(rule);var old=saved.rule();routes.configureStorage(saved.id(),new StorageRule(old.label(),kind,Set.of(),existing==null&&kind==StorageRule.Kind.SUPPLIES?50:old.priority()));}
                result="Storage #"+saved.id()+" saved at "+point+(loaded?" (container checked)":" (container will be checked on arrival)");
            }else{
                var existing=routes.deposits().stream().filter(s->s.endpoint().equals(point)).findFirst().orElse(null);
                if(existing==null)result="No storage registered at "+point;else{routes.removeDeposit(existing.id());result="Removed storage #"+existing.id()+" at "+point;}
            }
            MiningWaypoints.clearDeposit(ctx.worldData());logDirect(result);
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("add","remove","list");}
    @Override public String getShortDesc(){return "Register/remove dimension-specific storage endpoints on this bot";}
    @Override public List<String> getLongDesc(){return List.of("storagepoint add/remove <dimension> <x> <y> <z> [rule]","storagepoint list [page]","Normally sent by #bots from the manual player client. Loaded containers are validated immediately; unloaded endpoints are checked by the normal deposit process on arrival.");}
}

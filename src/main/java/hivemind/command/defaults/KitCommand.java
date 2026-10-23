/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;

import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.TaskLoadoutStore;
import java.util.*;
import java.util.stream.Stream;

/** Short bot-side command for task-specific reserve targets. */
public final class KitCommand extends Command {
    public KitCommand(IHiveMind bot){super(bot,"kit");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        var bot=(HiveMind)hivemind;
        if(bot.isPlayerClient())throw new CommandInvalidStateException("Send #kit through #bots <name> command or the controller");
        if(ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Join a world first");
        String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"list";
        try{
            TaskLoadoutStore kits=bot.getOperations().kits();
            switch(action){
                case "list","status"->{args.requireMax(0);logDirect("Current kit: "+bot.getOperations().kitName());for(var mode:TaskLoadoutStore.Mode.values())logDirect(mode.name().toLowerCase(Locale.ROOT)+": "+bot.getOperations().kitTargets(mode));}
                case "set"->{TaskLoadoutStore.Mode mode=TaskLoadoutStore.mode(args.getString());String item=TaskLoadoutStore.category(args.getString());int count=args.getAs(Integer.class);args.requireMax(0);kits.set(mode,item,count);bot.getOperations().reloadKits();logDirect("Kit "+mode.name().toLowerCase(Locale.ROOT)+" "+item+" = "+count+"; next refill uses this target");}
                case "reset"->{TaskLoadoutStore.Mode mode=TaskLoadoutStore.mode(args.getString());args.requireMax(0);kits.reset(mode);bot.getOperations().reloadKits();logDirect("Kit "+mode.name().toLowerCase(Locale.ROOT)+" reset to defaults");}
                case "now"->{args.requireMax(0);bot.getOperations().requestRestock();logDirect("Restocking the active "+bot.getOperations().kitName()+" kit");}
                default->throw new IllegalArgumentException("Use #kit list, #kit set <roam|mine|nether|hunt> <item> <count>, #kit reset <profile>, or #kit now");
            }
        }catch(IllegalArgumentException|IllegalStateException error){throw new CommandInvalidStateException(error.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("list","set","reset","now");}
    @Override public String getShortDesc(){return "Set food, tools and combat reserves for each bot task";}
    @Override public List<String> getLongDesc(){return List.of("#kit list — see effective roam, mine, Nether and hunt reserves","#kit set hunt crystals 32; #kit set mine blocks 128 — change a profile, 0 removes an item","#kit reset hunt — restore that profile; #kit now — refill the current profile from carried boxes or base storage");}
}

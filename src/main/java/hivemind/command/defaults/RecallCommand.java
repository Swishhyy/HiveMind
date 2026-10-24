/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import java.util.*;
import java.util.stream.Stream;
public final class RecallCommand extends Command {
    public RecallCommand(IHiveMind bot){super(bot,"recall");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{args.requireMax(0);var bot=(HiveMind)hivemind;if(bot.isPlayerClient()||ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Choose a connected autonomous bot");var task=bot.getReliability().checkpoint();task.recall(true);task.hold(true);bot.getAutonomy().pause(true);if(bot.getStalkProcess().isActive())bot.getStalkProcess().onLostControl();if(!bot.getOperations().requestRecall())logDirect("Recall retained; waiting for a usable base or safe inventory checkpoint");else logDirect("Returning to a known base; original work is retained until resume");}
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.empty();}
    @Override public String getShortDesc(){return "Return to a known base and hold the retained task until resume";}
    @Override public List<String> getLongDesc(){return List.of("recall — finish safe inventory work, return to a known base, then hold","resume — release the hold and resume the retained task","Use #bots all recall or the controller quick action for the fleet. The directive survives reconnects.");}
}

/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;

import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Short diagnostic command, also usable from trusted messages or controller quick commands. */
public final class WhyCommand extends Command {
    public WhyCommand(IHiveMind bot){super(bot,"why");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        args.requireMax(0);var bot=(HiveMind)hivemind;
        if(bot.isPlayerClient()||ctx.player()==null||ctx.world()==null)throw new CommandInvalidStateException("Select a connected bot");
        logDirect("Current "+bot.getOperations().kitName()+" kit; blocker: "+bot.getOperations().blocker()+"; next: "+bot.getOperations().nextAction());
        var events=bot.getOperations().journal().recent(12);if(events.isEmpty())logDirect("No recorded bot actions yet");
        else for(var event:events)logDirect(Instant.ofEpochMilli(event.time())+" "+event.kind()+": "+event.message());
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.empty();}
    @Override public String getShortDesc(){return "Explain recent bot decisions and storage failures";}
    @Override public List<String> getLongDesc(){return List.of("#why — display the current blocker, next action and recent task, storage, shulker and navigation events.");}
}

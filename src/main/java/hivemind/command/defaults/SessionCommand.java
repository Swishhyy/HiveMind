/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.bot.SessionRuntime;
import java.util.*;
import java.util.stream.Stream;
public final class SessionCommand extends Command {
    public SessionCommand(IHiveMind bot){super(bot,"session");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        List<String> parts=new ArrayList<>();while(args.hasAny())parts.add(args.getString());
        try{logDirect(SessionRuntime.get(ctx.minecraft().gameDirectory.toPath()).command("session "+String.join(" ",parts),System.currentTimeMillis()));}
        catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.empty();}
    @Override public String getShortDesc(){return "Log out, wait, or reconnect this Minecraft client";}
    @Override public List<String> getLongDesc(){return List.of("session logout - Stay offline until session reconnect.","session logout <5-86400 seconds> - Log out temporarily, then reconnect.","session reconnect/status - Rejoin the last multiplayer server or inspect the session.","The standalone controller connection remains active on the game menu. A closed Minecraft process needs a launcher to start it again.");}
}

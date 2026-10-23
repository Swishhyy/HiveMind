/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.api.IHiveMind;
import hivemind.HiveMind;
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
        try{
            var session=SessionRuntime.get(ctx.minecraft().gameDirectory.toPath());
            String command="session "+String.join(" ",parts);
            // Validate before capturing a mining queue; a failed command must not change resume intent.
            String result=session.command(command,System.currentTimeMillis());
            if(!parts.isEmpty()&&Set.of("logout","disconnect").contains(parts.get(0).toLowerCase(Locale.ROOT))&&ctx.player()!=null&&ctx.world()!=null){
                var jobs=((HiveMind)hivemind).getJobManager();session.resumeJob(jobs.runningJobs()?jobs.jobs().get(0).id():0);
            }
            logDirect(result);
        }
        catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.empty();}
    @Override public String getShortDesc(){return "Leave the server, wait, or reconnect while Minecraft stays open";}
    @Override public List<String> getLongDesc(){return List.of("session logout - Leave the server for the menu until session reconnect.","session logout <5-86400 seconds> - Leave temporarily, then reconnect.","session reconnect/status - Rejoin the last multiplayer server or inspect the session.","Minecraft and its controller connection stay running on the menu during manual or combat logout.");}
}

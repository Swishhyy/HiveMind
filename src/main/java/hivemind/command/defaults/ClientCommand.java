/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import java.util.*;
import java.util.stream.Stream;
public final class ClientCommand extends Command {
    public ClientCommand(IHiveMind bot){super(bot,"client");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        String role=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";args.requireMax(0);HiveMind bot=(HiveMind)hivemind;
        if(!Set.of("status","player","bot").contains(role))throw new CommandInvalidStateException("Use #client player/bot/status");
        if(!role.equals("status"))try{bot.getClientRole().requireRole(role);}catch(IllegalArgumentException e){throw new CommandInvalidStateException(e.getMessage());}
        if(!role.equals("status")&&!role.equals(bot.getClientRole().mode())){
            if(bot.getPlayerContext().world()!=null&&bot.getPlayerContext().player()!=null){bot.getJobManager().pauseJobs();bot.getAutonomy().pause(true);bot.getRoamProcess().hold(true);}
            bot.getFlight().cancel();bot.getPathingBehavior().cancelEverything();bot.getPathingBehavior().secretInternalSegmentCancel();bot.getInputOverrideHandler().clearAllKeys();bot.getLookBehavior().clearTarget();
            bot.getClientRole().set(role);
        }
        logDirect("Client mode: "+bot.getClientRole().mode()+(bot.getClientRole().locked()?" (player JAR, locked)":"")+(bot.isPlayerClient()?". Sharing terrain and sightings; you control this character.":". Use #roam on, #resume or a task command to start work."));
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return ((HiveMind)hivemind).getClientRole().locked()?Stream.of("player","status"):Stream.of("player","bot","status");}
    @Override public String getShortDesc(){return "Choose manual player sharing or autonomous bot mode";}
    @Override public List<String> getLongDesc(){return List.of("client player/bot/status","Saved per Minecraft instance. Player mode shares loaded terrain, position and sightings through #network, but disables autonomous movement, combat, inventory and session control.","The player JAR is locked to manual sharing. The standard JAR preserves local role choices for upgrade compatibility. Switching modes parks existing work; remote commands cannot change this role.");}
}

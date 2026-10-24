/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.reliability.DiagnosticBundle;
import hivemind.utils.mining.MiningRouteAccess;
import java.util.*;
import java.util.stream.Stream;

public final class DoctorCommand extends Command {
    public DoctorCommand(IHiveMind bot){super(bot,"doctor");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        var bot=(HiveMind)hivemind;if(ctx.player()==null||ctx.world()==null||bot.isPlayerClient())throw new CommandInvalidStateException("Choose a connected autonomous bot");
        String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        try{
            if(action.equals("status")){args.requireMax(0);bot.getReliability().details().forEach((k,v)->logDirect(k+": "+v));return;}
            if(action.equals("export")){args.requireMax(0);var file=DiagnosticBundle.create(bot.getDirectory().resolve("diagnostics"),MiningRouteAccess.root(ctx),ctx.player().getUUID(),bot.getReliability().details(),bot.getReliability().trace());logDirect("Support report saved: "+file.toAbsolutePath());return;}
            if(action.equals("retry")){args.requireMax(0);bot.getSurvivalProcess().retry();bot.getSculkProcess().retry();bot.getOperations().retrySupplies();logDirect("Recovery conditions will be reassessed; saved intent is retained");return;}
            throw new IllegalArgumentException("Use doctor status/export/retry");
        }catch(java.io.IOException|IllegalStateException|IllegalArgumentException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.of("status","export","retry");}
    @Override public String getShortDesc(){return "Inspect recovery, export a support report or reassess failed supplies";}
    @Override public List<String> getLongDesc(){return List.of("doctor status/export/retry","Support reports contain task, inventory, route and region records; network keys, launcher credentials and chat are excluded.");}
}

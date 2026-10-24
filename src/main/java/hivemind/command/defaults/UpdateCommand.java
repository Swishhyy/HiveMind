/* HiveMind, LGPL-3.0. */
package hivemind.command.defaults;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.*;
import hivemind.utils.update.UpdateRuntime;
import java.util.*;
import java.util.stream.Stream;
public final class UpdateCommand extends Command {
    public UpdateCommand(IHiveMind bot){super(bot,"update");}
    @Override public void execute(String label,IArgConsumer args)throws CommandException{
        try{
            var service=UpdateRuntime.service();String action=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
            switch(action){
                case "status" -> args.requireMax(0);
                case "check","install" -> {String expected=action.equals("install")&&args.hasAny()?args.getString():"";args.requireMax(0);if(!expected.isBlank()&&!expected.matches("[0-9]+(?:\\.[0-9]+){3}"))throw new IllegalArgumentException("Use a four-part release version");service.check(action.equals("install"),expected);}
                case "unpin" -> {args.requireMax(0);service.unpin();}
                case "auto" -> {String value=args.getString();args.requireMax(0);if(!Set.of("on","off").contains(value))throw new IllegalArgumentException("Use update auto on/off");service.consent(value.equals("on"));}
                default -> throw new IllegalArgumentException("Use update status/check/install/auto on/off");
            }
            logDirect(service.summary());
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args){return Stream.empty();}
    @Override public String getShortDesc(){return "Check and install HiveMind releases, or change automatic updates";}
    @Override public List<String> getLongDesc(){return List.of("update status/check/install; update auto on/off.","Updates verify the release hash and Fabric 26.3 build identity. The helper waits until Minecraft exits, keeps a backup outside mods, and replaces this mod for the next launch.");}
}

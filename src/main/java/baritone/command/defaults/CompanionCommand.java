/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.process.CompanionProcess;
import baritone.utils.bot.BotLocations;
import baritone.utils.mining.MiningRouteAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.stream.Stream;

/** Commands remain the task interface; configuring endpoints is local to the bot account. */
public final class CompanionCommand extends Command {
    public CompanionCommand(IBaritone bot) { super(bot,"recover","deliver","escort","gather","repair","station"); }
    private Baritone bot(){return (Baritone)baritone;}
    private void local(){if(bot().getJobManager().requestOwner()!=null)throw new IllegalArgumentException("Endpoint, boundary, and recovery-reset edits must be made locally on the bot account");}
    private void report(String message){logDirect(message);UUID owner=bot().getJobManager().requestOwner();if(owner!=null)bot().getBotControl().reply(owner,message);}
    @Override public void execute(String label,IArgConsumer args)throws CommandException {
        try {
            var process=bot().getCompanionProcess();var locations=MiningRouteAccess.locations(ctx);
            String action=args.hasAny()?args.peekString().toLowerCase(Locale.ROOT):"status";
            if(label.equals("station")){station(args,locations);return;}
            if(label.equals("repair")){
                if(args.hasAny())args.getString();args.requireMax(0);
                switch(action){
                    case "set"->{local();if(bot().getRepairProcess().isActive())throw new IllegalStateException("Stop the repair trip before moving its station");locations.setStation("repair",MiningRouteAccess.endpoint(ctx,ctx.playerFeet()));report("Mending/XP station saved at "+ctx.playerFeet());}
                    case "clear"->{local();if(bot().getRepairProcess().isActive())throw new IllegalStateException("Stop the repair trip before clearing its station");locations.setStation("repair",null);report("Repair station removed");}
                    case "now"->bot().getRepairProcess().start();
                    case "status"->report(bot().getRepairProcess().result()+"; station "+locations.station("repair"));
                    default->throw new IllegalArgumentException("Use repair set, now, status, or clear");
                }return;
            }
            if(action.equals("status")){
                if(args.hasAny())args.getString();args.requireMax(0);
                if(label.equals("recover"))report("Recorded death: "+locations.death()+"; "+process.status());else report(process.status());return;
            }
            if(action.equals("stop")){
                args.getString();args.requireMax(0);
                CompanionProcess.Kind expected=switch(label){case "recover"->CompanionProcess.Kind.RECOVER;case "deliver"->CompanionProcess.Kind.DELIVER;case "escort"->CompanionProcess.Kind.ESCORT;default->CompanionProcess.Kind.GATHER;};
                if(process.kind()!=expected)throw new IllegalStateException("No "+label+" task is running");bot().getPathingBehavior().cancelEverything();report(label+" stopped");return;
            }
            switch(label){
                case "recover"->{
                    args.getString();args.requireMax(0);
                    if(action.equals("reset")){local();if(process.isRecovering())throw new IllegalStateException("Stop recovery before resetting its attempts");var death=locations.death();if(death==null)throw new IllegalStateException("No recorded death");locations.setDeath(new BotLocations.Death(death.point(),death.time(),death.items(),0));report("Recovery attempts reset after inspection");}
                    else if(action.equals("now"))process.recover();else throw new IllegalArgumentException("Use recover now, status, stop, or reset");
                }
                case "deliver"->{
                    if(action.equals("handoff")){args.getString();args.requireMax(0);process.confirmHandoff();}
                    else {args.requireMin(3);String player=args.getString(),item=args.getString();int quantity=args.getAs(Integer.class);BlockPos meeting=args.hasAny()?args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet()):null;args.requireMax(0);process.deliver(player,item,quantity,meeting);}
                }
                case "escort"->{String player=args.getString();args.requireMax(0);process.escort(player);}
                case "gather"->{
                    boolean preview=action.equals("plan");if(preview)args.getString();args.requireMin(2);int quantity=args.getAs(Integer.class);String item=args.getString();args.requireMax(0);
                    if(preview){var plan=process.plan(item,quantity);report("Gather plan: "+plan.actions().size()+" dependency steps; needed "+plan.required());for(var step:plan.actions().stream().limit(12).toList())report(step.method()+" "+step.amount()+" "+step.item());}
                    else process.gather(item,quantity);
                }
                default->throw new IllegalArgumentException("Unknown companion command");
            }
        }catch(IllegalArgumentException|IllegalStateException e){throw new CommandInvalidStateException(e.getMessage());}
    }
    private void station(IArgConsumer args,BotLocations locations)throws CommandException{
        String type=args.hasAny()?args.getString().toLowerCase(Locale.ROOT):"status";
        if(type.equals("status")){args.requireMax(0);for(String key:List.of("crafting","furnace","repair"))report(key+": "+locations.station(key));return;}
        local();if(bot().getWorkstationProcess().isActive()||bot().getRepairProcess().isActive())throw new IllegalStateException("Stop workstation/repair trips before editing stations");
        if(type.equals("clear")){String name=args.getString().toLowerCase(Locale.ROOT);args.requireMax(0);locations.setStation(name,null);report(name+" station removed");return;}
        if(!Set.of("crafting","furnace","repair").contains(type))throw new IllegalArgumentException("Use station crafting/furnace/repair [x y z], status, or clear <type>");
        BlockPos point=args.hasAny()?args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet()):type.equals("repair")?ctx.playerFeet():ctx.getSelectedBlock().orElseThrow(()->new IllegalStateException("Look at the crafting table or furnace to register"));args.requireMax(0);
        if(!type.equals("repair")&&(!ctx.world().hasChunkAt(point)||!ctx.world().getBlockState(point).is(type.equals("crafting")?Blocks.CRAFTING_TABLE:Blocks.FURNACE)))throw new IllegalStateException("Select a loaded "+type+" workstation");
        locations.setStation(type,MiningRouteAccess.endpoint(ctx,point));bot().getPathingBehavior().softCancelIfSafe();report(type+" station saved at "+point);
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args)throws CommandException{
        if(!args.hasAtMostOne())return Stream.empty();
        var helper=new TabCompleteHelper().append("status","stop");
        switch(label){case "recover"->helper.append("now","reset");case "repair"->helper.append("now","set","clear");case "station"->helper.append("crafting","furnace","repair","clear");case "gather"->helper.append("plan");case "deliver"->helper.append("handoff");default->{}}
        if((label.equals("escort")||label.equals("deliver"))&&ctx.minecraft().getConnection()!=null)ctx.minecraft().getConnection().getOnlinePlayers().forEach(info->helper.append(info.getProfile().name()));
        return helper.filterPrefix(args.getString()).stream();
    }
    @Override public String getShortDesc(){return "Recover inventory, deliver items, escort players, gather recipes, and configure workstations";}
    @Override public List<String> getLongDesc(){return List.of("recover now/status/stop/reset - Return to the last recorded death; attempts and search radius are bounded.","deliver <player> <item> <quantity> [meeting x y z] - Fetch registered stock and approach an online player.","deliver handoff - Confirm pickup when alone beside the recipient; deliveries require confirmed recipient pickup.","escort <player> - Follow a visible player; defense mode can protect against an observed attacker.","gather <quantity> <item>; gather plan <quantity> <item> - Gather supported resources, craft/smelt at registered stations, and deposit the product.","station crafting/furnace [x y z] - Register a table/furnace; station repair [x y z] - Save an XP point.","station status; station clear <type>; repair set/now/status/clear.","Settings are available through Mod Menu > HiveMind > Configure and existing #set commands.");}
}

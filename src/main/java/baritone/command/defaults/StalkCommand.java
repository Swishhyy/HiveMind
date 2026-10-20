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
import baritone.process.StalkProcess;
import baritone.utils.social.CombatStyle;
import net.minecraft.core.BlockPos;
import java.util.*;
import java.util.stream.Stream;

public final class StalkCommand extends Command {
    public StalkCommand(IBaritone bot) { super(bot,"stalk","kill"); }
    private Baritone bot() { return (Baritone)baritone; }
    private void local() {
        if(bot().getJobManager().requestOwner()!=null)throw new IllegalStateException("Sighting, base, and reporting configuration edits must be made locally");
    }
    private void report(String message) {
        logDirect(message);UUID owner=bot().getJobManager().requestOwner();if(owner!=null)bot().getBotControl().reply(owner,message);
    }
    @Override public void execute(String label,IArgConsumer args) throws CommandException {
        StalkProcess process=bot().getStalkProcess();boolean kill=label.equalsIgnoreCase("kill");
        try {
            if(kill&&!args.hasAny())throw new IllegalStateException("Use kill <player>, status, or stop");
            String first=args.hasAny()?args.getString():"random";String action=first.toLowerCase(Locale.ROOT);
            if(action.equals("status")) { args.requireMax(0);report(process.status());return; }
            if(action.equals("stop")) { args.requireMax(0);process.onLostControl();bot().getPathingBehavior().softCancelIfSafe();report("Tracking and ambush stopped");return; }
            if(kill) { CombatStyle style=CombatStyle.parse(args.hasAny()?args.getString():Baritone.settings().killCombatStyle.value);args.requireMax(0);process.startHunt(first,style);return; }
            switch(action) {
                case "sightings","history" -> {
                    String name=args.hasAny()?args.getString():null;args.requireMax(0);
                    var saved=process.history(name);report(saved.isEmpty()?"No saved sightings":"Saved sightings (up to 12 places):");
                    for(var place:saved)report(place.name()+" - "+place.label()+" at "+place.point().x()+" "+place.point().y()+" "+place.point().z()
                            +" ("+place.point().dimension()+"); visits "+place.visits()+", observed "+place.observedSeconds()+"s");
                }
                case "check" -> { String name=args.getString();args.requireMax(0);process.checkPlaces(name); }
                case "base" -> {
                    local();String name=args.getString();BlockPos point=args.hasAny()?args.getDatatypePost(RelativeBlockPos.INSTANCE,ctx.playerFeet()):null;args.requireMax(0);
                    process.confirmBase(name,point);report("Confirmed base saved for "+name);
                }
                case "forget" -> {
                    local();String name=args.getString();args.requireMax(0);
                    if(process.isActive())throw new IllegalStateException("Stop tracking before forgetting sightings");
                    process.memory().forget(name.equalsIgnoreCase("all")?null:process.identity(name));report("Removed saved sightings for "+name);
                }
                case "report" -> {
                    String recipient=args.hasAny()?args.getString():"status";args.requireMax(0);
                    if(recipient.equalsIgnoreCase("status"))report("Private sighting recipient: "+process.memory().reportTo()+"; whisper-started tasks reply to their controller");
                    else {
                        local();UUID id=recipient.equalsIgnoreCase("off")?null:process.identity(recipient);
                        if(id!=null&&!bot().getBotControl().isTrusted(id))throw new IllegalStateException("Sighting recipient must be a trusted controller");
                        process.memory().reportTo(id);report(id==null?"Local sighting messages only for locally started tasks":"Private sighting reports configured for "+recipient);
                    }
                }
                default -> { args.requireMax(0);process.start(first); }
            }
        } catch(IllegalArgumentException|IllegalStateException error) { throw new CommandInvalidStateException(error.getMessage()); }
    }
    @Override public Stream<String> tabComplete(String label,IArgConsumer args) throws CommandException {
        if(ctx.minecraft().getConnection()==null)return Stream.empty();
        if(label.equalsIgnoreCase("kill")&&args.hasExactly(2)) { args.getString();return new TabCompleteHelper().append("auto","melee","crystal","anchor").filterPrefix(args.getString()).stream(); }
        if(!args.hasAtMostOne())return Stream.empty();
        var choices=new TabCompleteHelper().append("status","stop");
        if(!label.equalsIgnoreCase("kill"))choices.append("random","sightings","history","check","base","forget","report");
        return choices.append(ctx.minecraft().getConnection().getOnlinePlayers().stream().map(info->info.getProfile().name()))
                .filterPrefix(args.getString()).stream();
    }
    @Override public String getShortDesc() { return "Stealth stalking, private sighting memory, and an explicit named-player ambush"; }
    @Override public List<String> getLongDesc() {
        return List.of("stalk <player|random> - Observe from open line-of-sight positions about 40 blocks away.",
                "It crouches only where another player could hear or possibly see it, and can drink hotbar invisibility potions.",
                "Sustained player gaze causes a scatter and a new approach. Minimal-evidence mode avoids terrain edits and closed doors.",
                "stalk sightings [player] - List saved observations and possible/confirmed bases for this server.",
                "stalk check <player> - Check saved places, including when that player is offline; no global coordinates are revealed.",
                "stalk base <player> [x y z] - Locally confirm a base at supplied or previously observed coordinates.",
                "stalk forget <player|all>; stalk report <trusted-player|off|status> - Local memory/report configuration.",
                "kill <player> [auto|melee|crystal|anchor] - Stalk, approach an isolated player looking away, and engage using carried supplies.",
                "An engaged fight only retreats on low health or a second nearby player; a totem is kept in the offhand.",
                "Crystal uses obsidian/bedrock bases and real end crystals; anchor places, charges, and detonates outside respawn dimensions.",
                "Explosive combat can leave craters/obsidian and uses blast-exposure, health, bystander, and protected-area checks.",
                "kill status/stop; stalk status/stop - Inspect or cancel. Global pause/stop still apply.",
                "Hunts have health, weapon, approach, combat, and attempt limits. Trusted controllers are protected by default.",
                "Only observations from this client are remembered. Possible bases are inferences; they do not establish ownership.");
    }
}

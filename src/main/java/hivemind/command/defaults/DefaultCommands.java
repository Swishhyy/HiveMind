/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.command.defaults;

import hivemind.api.IHiveMind;
import hivemind.api.command.ICommand;

import java.util.*;

public final class DefaultCommands {

    private DefaultCommands() {
    }

    public static List<ICommand> createAll(IHiveMind hivemind) {
        Objects.requireNonNull(hivemind);
        List<ICommand> commands = new ArrayList<>(Arrays.asList(
                new HelpCommand(hivemind),
                new SetCommand(hivemind),
                new CommandAlias(hivemind, Arrays.asList("modified", "mod", "hivemind", "modifiedsettings"), "List modified settings", "set modified"),
                new CommandAlias(hivemind, "reset", "Reset all settings or just one", "set reset"),
                new GoalCommand(hivemind),
                new GotoCommand(hivemind),
                new PathCommand(hivemind),
                new ProcCommand(hivemind),
                new ETACommand(hivemind),
                new VersionCommand(hivemind),
                new RepackCommand(hivemind),
                new BuildCommand(hivemind),
                //new SchematicaCommand(hivemind),
                new LitematicaCommand(hivemind),
                new ComeCommand(hivemind),
                new AxisCommand(hivemind),
                new ForceCancelCommand(hivemind),
                new GcCommand(hivemind),
                new InvertCommand(hivemind),
                new TunnelCommand(hivemind),
                new RenderCommand(hivemind),
                new FarmCommand(hivemind),
                new FollowCommand(hivemind),
                new StalkCommand(hivemind),
                new NetworkCommand(hivemind),
                new ClientCommand(hivemind),
                new ScoutCommand(hivemind),
                new UpdateCommand(hivemind),
                new SessionCommand(hivemind),
                new MapMarkerCommand(hivemind),
                new RoamCommand(hivemind),
                new PickupCommand(hivemind),
                new ExploreFilterCommand(hivemind),
                new ReloadAllCommand(hivemind),
                new SaveAllCommand(hivemind),
                new ExploreCommand(hivemind),
                new BlacklistCommand(hivemind),
                new FindCommand(hivemind),
                new MineCommand(hivemind),
                new DepositCommand(hivemind),
                new StorageCommand(hivemind),
                new TrustCommand(hivemind),
                new JobCommand(hivemind),
                new SurvivalCommand(hivemind),
                new CompanionCommand(hivemind),
                new MineZoneCommand(hivemind),
                new PortalCommand(hivemind),
                new ProtectCommand(hivemind),
                new ClickCommand(hivemind),
                new SurfaceCommand(hivemind),
                new ThisWayCommand(hivemind),
                new WaypointsCommand(hivemind),
                new CommandAlias(hivemind, "sethome", "Sets your home waypoint", "waypoints save home"),
                new CommandAlias(hivemind, "home", "Path to your home waypoint", "waypoints goto home"),
                new SelCommand(hivemind),
                new ElytraCommand(hivemind)
        ));
        ExecutionControlCommands prc = new ExecutionControlCommands(hivemind);
        commands.add(prc.pauseCommand);
        commands.add(prc.resumeCommand);
        commands.add(prc.pausedCommand);
        commands.add(prc.cancelCommand);
        return Collections.unmodifiableList(commands);
    }
}

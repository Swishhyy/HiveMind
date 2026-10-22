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

import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.datatypes.RelativeBlockPos;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import hivemind.process.DepositProcess;
import hivemind.utils.mining.MiningWaypoints;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class DepositCommand extends Command {
    public DepositCommand(IHiveMind hivemind) {
        super(hivemind, "deposit");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "now";
        HiveMind instance = (HiveMind) hivemind;
        DepositProcess process = instance.getDepositProcess();
        if(action.equals("point")){instance.getCommandManager().getCommand("storagepoint").execute("storagepoint",args);return;}
        if (action.equals("set") || action.equals("add")) {
            BlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.playerFeet())
                    : ctx.getSelectedBlock().orElseThrow(() -> new CommandInvalidStateException("Look directly at the chest to save"));
            args.requireMax(0);
            if (process.isActive()) {
                throw new CommandInvalidStateException("Stop the deposit trip before changing its destination");
            }
            if (!ctx.world().hasChunkAt(pos) || !DepositProcess.supports(ctx.world().getBlockState(pos).getBlock())) {
                throw new CommandInvalidStateException("The selected position must be a loaded chest, trapped chest, copper chest (any oxidation or wax state), or barrel");
            }
            MiningRouteAccess.deposits(ctx); // Import an older single chest before adding to it.
            WorldRoutes.Storage saved = MiningRouteAccess.store(ctx).addDeposit(MiningRouteAccess.endpoint(ctx, pos));
            MiningWaypoints.clearDeposit(ctx.worldData());
            instance.getPathingBehavior().softCancelIfSafe();
            logDirect("Storage #" + saved.id() + " saved at " + pos + " in " + saved.endpoint().dimension());
        } else if (action.equals("remove")) {
            args.requireMin(1);
            int id = args.getAs(Integer.class);
            args.requireMax(0);
            if (process.isActive()) throw new CommandInvalidStateException("Stop the deposit trip before removing storage");
            MiningRouteAccess.deposits(ctx);
            if (!MiningRouteAccess.store(ctx).removeDeposit(id)) {
                throw new CommandInvalidStateException("No storage #" + id + "; use deposit list to see saved chests");
            }
            MiningWaypoints.clearDeposit(ctx.worldData());
            instance.getPathingBehavior().softCancelIfSafe();
            logDirect("Removed storage #" + id);
        } else {
            args.requireMax(0);
            switch (action) {
                case "clear":
                    if (process.isActive()) {
                        throw new CommandInvalidStateException("Stop the deposit trip before clearing its destination");
                    }
                    MiningWaypoints.clearDeposit(ctx.worldData());
                    MiningRouteAccess.store(ctx).clearDeposits();
                    instance.getPathingBehavior().softCancelIfSafe();
                    logDirect("All storage chests cleared for this server/save");
                    break;
                case "status":
                case "list":
                    List<WorldRoutes.Storage> saved = MiningRouteAccess.deposits(ctx);
                    if (saved.isEmpty()) logDirect("No storage chests saved for this server/save");
                    for (WorldRoutes.Storage storage : saved) {
                        WorldRoutes.Endpoint point = storage.endpoint();
                        logDirect("#" + storage.id() + " " + storage.rule().label() + " [" + storage.rule().kind().name().toLowerCase(Locale.ROOT) + "] " + point.dimension() + " " + point.x() + " " + point.y() + " " + point.z());
                    }
                    if (process.isActive()) logDirect(process.displayName());
                    break;
                case "now":
                case "all":
                    try {
                        process.start(action.equals("all"));
                    } catch (IllegalStateException e) {
                        throw new CommandInvalidStateException(e.getMessage());
                    }
                    break;
                default:
                    throw new CommandInvalidStateException("Use deposit add/set, remove <id>, list, now, all, status, or clear");
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasAtMostOne()) return new TabCompleteHelper().append("add", "set", "remove", "list", "now", "all", "status", "clear")
                .filterPrefix(args.getString()).stream();
        if (args.getString().equalsIgnoreCase("remove") && args.hasAtMostOne()) {
            return new TabCompleteHelper().append(MiningRouteAccess.deposits(ctx).stream().map(storage -> Integer.toString(storage.id())))
                    .filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Manage storage chests and unload mining inventory";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of("deposit add/set [x y z] - Add the selected chest or exact position; repeat to save more chests.",
                "Supports regular and trapped chests, barrels, and all waxed/unwaxed copper chest variants.",
                "deposit [now] - Unload resources, including mining output in the hotbar; keep reserved supplies.",
                "deposit all - Stop mining and empty the main inventory and hotbar; worn equipment stays equipped.",
                "deposit remove <id> - Remove one numbered storage entry; IDs do not change after removal.",
                "deposit list/status - List storage IDs, dimensions, coordinates, and the current trip.",
                "deposit clear - Forget all storage entries for this server/save.",
                "With mineAutoDeposit enabled, mining returns automatically when inventory space runs low.",
                "A full, missing, blocked, or unreachable chest is skipped; mining stops if every saved chest is unavailable.");
    }
}

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
import baritone.process.DepositProcess;
import baritone.utils.mining.MiningWaypoints;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class DepositCommand extends Command {
    public DepositCommand(IBaritone baritone) {
        super(baritone, "deposit");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "now";
        Baritone instance = (Baritone) baritone;
        DepositProcess process = instance.getDepositProcess();
        if (action.equals("set")) {
            BlockPos pos = args.hasAny() ? args.getDatatypePost(RelativeBlockPos.INSTANCE, ctx.playerFeet())
                    : ctx.getSelectedBlock().orElseThrow(() -> new CommandInvalidStateException("Look directly at the chest to save"));
            args.requireMax(0);
            if (process.isActive()) {
                throw new CommandInvalidStateException("Stop the deposit trip before changing its destination");
            }
            if (!ctx.world().hasChunkAt(pos) || !DepositProcess.supports(ctx.world().getBlockState(pos).getBlock())) {
                throw new CommandInvalidStateException("The selected position must be a loaded chest, trapped chest, or barrel");
            }
            MiningWaypoints.setDeposit(ctx.worldData(), pos);
            instance.getPathingBehavior().softCancelIfSafe();
            logDirect("Deposit chest saved at " + pos + " for this world and dimension");
        } else {
            args.requireMax(0);
            switch (action) {
                case "clear":
                    if (process.isActive()) {
                        throw new CommandInvalidStateException("Stop the deposit trip before clearing its destination");
                    }
                    MiningWaypoints.clearDeposit(ctx.worldData());
                    logDirect("Deposit chest cleared");
                    break;
                case "status":
                    BlockPos pos = MiningWaypoints.deposit(ctx.worldData());
                    logDirect(pos == null ? "No deposit chest saved in this dimension" : "Deposit chest: " + pos);
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
                    throw new CommandInvalidStateException("Use deposit set, now, all, status, or clear");
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("set", "now", "all", "status", "clear")
                .filterPrefix(args.getString()).stream() : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Save a deposit chest and unload mining inventory";
    }

    @Override
    public List<String> getLongDesc() {
        return List.of("deposit set [x y z] - Save the chest you are looking at, or an exact chest position.",
                "deposit [now] - Unload resources, retaining hotbar items, food, and equipment by default.",
                "deposit all - Stop mining and empty the main inventory and hotbar; worn equipment stays equipped.",
                "deposit status / clear - Show or forget the saved chest.",
                "With mineAutoDeposit enabled, mining returns automatically when inventory space runs low.",
                "A full, missing, blocked, or unreachable chest stops mining without dropping items.");
    }
}

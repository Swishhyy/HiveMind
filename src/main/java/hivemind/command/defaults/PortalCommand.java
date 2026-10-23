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
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes;
import hivemind.utils.bot.PortalLinks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class PortalCommand extends Command {
    public PortalCommand(IHiveMind hivemind) { super(hivemind, "portal"); }

    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        try {
            WorldRoutes routes = MiningRouteAccess.store(ctx);
            switch (action) {
                case "set":
                    String dimension = MiningRouteAccess.dimension(ctx);
                    if (!dimension.equals(WorldRoutes.OVERWORLD) && !dimension.equals(WorldRoutes.NETHER)) {
                        throw new IllegalStateException("Save Nether portals in the Overworld or Nether");
                    }
                    BlockPos selected = ctx.getSelectedBlock().orElse(ctx.playerFeet());
                    if (!ctx.world().getBlockState(selected).is(Blocks.NETHER_PORTAL)) {
                        throw new IllegalStateException("Look at the purple portal blocks, or stand inside an active portal");
                    }
                    for (int i = 0; i < 21 && ctx.world().getBlockState(selected.below()).is(Blocks.NETHER_PORTAL); i++) selected = selected.below();
                    routes.setPortal(MiningRouteAccess.endpoint(ctx, selected));
                    logDirect("Saved mining portal: " + routes.portal(dimension));
                    break;
                case "clear":
                    routes.clearPortal(MiningRouteAccess.dimension(ctx));
                    new PortalLinks(MiningRouteAccess.root(ctx)).forgetDimension(MiningRouteAccess.dimension(ctx));
                    logDirect("Forgot saved portal and observed links in this dimension");
                    break;
                case "status":
                    logDirect("Overworld portal: " + routes.portal(WorldRoutes.OVERWORLD));
                    logDirect("Nether portal: " + routes.portal(WorldRoutes.NETHER));
                    for(var link:new PortalLinks(MiningRouteAccess.root(ctx)).recent().stream().limit(8).toList())
                        logDirect("Observed: "+link.from()+" -> "+link.to()+" ("+java.time.Instant.ofEpochMilli(link.seen())+")");
                    break;
                default:
                    throw new IllegalStateException("Use portal set, status, or clear");
            }
        } catch (IllegalStateException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
    }

    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("set", "status", "clear")
                .filterPrefix(args.getString()).stream() : Stream.empty();
    }
    @Override public String getShortDesc() { return "Save Nether portal routes for automatic mining trips"; }
    @Override public List<String> getLongDesc() {
        return List.of("portal set - Save the active portal you are looking at in this dimension.",
                "portal status / clear - List routes, or forget this dimension's saved portal.",
                "Ancient debris mining automatically travels to the Nether through saved or known portals.",
                "The actual arrival portal is saved for the return trip. No portal is automatically built or lit.");
    }
}

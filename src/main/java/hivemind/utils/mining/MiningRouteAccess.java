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

package hivemind.utils.mining;

import hivemind.api.utils.IPlayerContext;
import hivemind.utils.bot.BotLocations;
import hivemind.cache.WorldData;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Client-thread adapter between Minecraft positions/worlds and the dependency-free route store. */
public final class MiningRouteAccess {
    private static final Map<Path, WorldRoutes> STORES = new ConcurrentHashMap<>();
    private static final Map<Path, BotLocations> LOCATIONS = new ConcurrentHashMap<>();
    public static BotLocations locations(IPlayerContext ctx) { return LOCATIONS.computeIfAbsent(root(ctx), BotLocations::new); }
    private MiningRouteAccess() {}

    public static Path root(IPlayerContext ctx) {
        if (!(ctx.worldData() instanceof WorldData data)) {
            throw new IllegalStateException("No world storage is available for dimension travel");
        }
        // WorldProvider stores <save/server>/<namespace>/<dimension_height>.
        return data.directory.getParent().getParent().toAbsolutePath().normalize();
    }

    public static WorldRoutes store(IPlayerContext ctx) {
        return STORES.computeIfAbsent(root(ctx), WorldRoutes::new);
    }

    public static String dimension(IPlayerContext ctx) {
        return ctx.world().dimension().identifier().toString();
    }

    public static WorldRoutes.Endpoint endpoint(IPlayerContext ctx, BlockPos pos) {
        return new WorldRoutes.Endpoint(dimension(ctx), pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos position(WorldRoutes.Endpoint endpoint) {
        return new BlockPos(endpoint.x(), endpoint.y(), endpoint.z());
    }

    public static WorldRoutes.Endpoint deposit(IPlayerContext ctx) {
        return deposits(ctx).stream().map(WorldRoutes.Storage::endpoint).findFirst().orElse(null);
    }

    public static List<WorldRoutes.Storage> deposits(IPlayerContext ctx) {
        WorldRoutes routes = store(ctx);
        if (!routes.storageConfigured()) {
            BlockPos legacy = MiningWaypoints.deposit(ctx.worldData());
            if (legacy != null) {
                routes.addDeposit(endpoint(ctx, legacy));
            }
        }
        return routes.deposits();
    }
}

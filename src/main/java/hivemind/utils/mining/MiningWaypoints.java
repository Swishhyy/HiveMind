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

import hivemind.HiveMind;
import hivemind.api.cache.IWaypoint;
import hivemind.api.cache.IWaypointCollection;
import hivemind.api.cache.IWorldData;
import hivemind.api.cache.Waypoint;
import hivemind.api.utils.BetterBlockPos;
import hivemind.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Uses HiveMind's existing persistent, server- and dimension-scoped waypoint storage. */
public final class MiningWaypoints {
    private static final String DEPOSIT = "__hivemind_deposit";
    private static final String PROTECTION = "__hivemind_protect_";

    private MiningWaypoints() {}

    public static BlockPos deposit(IWorldData world) {
        if (world == null) {
            return null;
        }
        return world.getWaypoints().getByTag(IWaypoint.Tag.USER).stream()
                .filter(w -> DEPOSIT.equals(w.getName()))
                .max(Comparator.comparingLong(IWaypoint::getCreationTimestamp))
                .map(IWaypoint::getLocation).orElse(null);
    }

    public static void setDeposit(IWorldData world, BlockPos pos) {
        clearDeposit(world);
        world.getWaypoints().addWaypoint(new Waypoint(DEPOSIT, IWaypoint.Tag.USER, new BetterBlockPos(pos)));
    }

    public static void clearDeposit(IWorldData world) {
        removeNamed(world.getWaypoints(), DEPOSIT, false);
    }

    public static void protect(IWorldData world, BlockPos pos, int radius) {
        new ConstructionPolicy.Area(pos.getX(), pos.getY(), pos.getZ(), radius); // validate before persisting
        world.getWaypoints().addWaypoint(new Waypoint(PROTECTION + radius, IWaypoint.Tag.USER, new BetterBlockPos(pos)));
    }

    public static void clearProtection(IWorldData world) {
        removeNamed(world.getWaypoints(), PROTECTION, true);
    }

    private static void removeNamed(IWaypointCollection waypoints, String name, boolean prefix) {
        new ArrayList<>(waypoints.getByTag(IWaypoint.Tag.USER)).stream()
                .filter(w -> prefix ? w.getName().startsWith(name) : w.getName().equals(name))
                .forEach(waypoints::removeWaypoint);
    }

    public static List<ConstructionPolicy.Area> protection(IPlayerContext ctx) {
        IWorldData world = ctx.worldData();
        List<ConstructionPolicy.Area> areas = new ArrayList<>();
        if (world == null) {
            return List.of();
        }
        for (IWaypoint waypoint : world.getWaypoints().getByTag(IWaypoint.Tag.USER)) {
            if (waypoint.getName().startsWith(PROTECTION)) {
                try {
                    int radius = Integer.parseInt(waypoint.getName().substring(PROTECTION.length()));
                    BlockPos p = waypoint.getLocation();
                    areas.add(new ConstructionPolicy.Area(p.getX(), p.getY(), p.getZ(), radius));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed user-edited markers rather than breaking the pathfinding thread.
                }
            }
        }
        int radius = Math.max(0, Math.min(256, HiveMind.settings().depositProtectionRadius.value));
        String dimension = MiningRouteAccess.dimension(ctx);
        for (WorldRoutes.Storage storage : MiningRouteAccess.deposits(ctx)) {
            WorldRoutes.Endpoint chest = storage.endpoint();
            if (chest.dimension().equals(dimension)) {
                areas.add(new ConstructionPolicy.Area(chest.x(), chest.y(), chest.z(), radius));
            }
        }
        for (String name : List.of("repair", "crafting", "furnace")) {
            WorldRoutes.Endpoint point = MiningRouteAccess.locations(ctx).station(name);
            if (point != null && point.dimension().equals(dimension)) areas.add(new ConstructionPolicy.Area(point.x(), point.y(), point.z(), 3));
        }
        return List.copyOf(areas);
    }
}

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

package baritone.utils.mining;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Route persistence and transition guards must be testable without a connected Minecraft client. */
public final class WorldRouteChecks {
    public static void main(String[] args) throws IOException {
        runAll();
        System.out.println("World route checks passed: persistence, server isolation, dimensions, transition cancellation");
    }

    public static void runAll() throws IOException {
        Path temporary = Files.createTempDirectory("baritone-routes-test-");
        try {
            Path serverA = temporary.resolve("server-a");
            WorldRoutes routes = new WorldRoutes(serverA);
            WorldRoutes.Endpoint chest = new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD, -320, 64, 1280);
            WorldRoutes.Endpoint overworld = new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD, -300, 65, 1200);
            WorldRoutes.Endpoint nether = new WorldRoutes.Endpoint(WorldRoutes.NETHER, -42, 72, 148);
            routes.setDeposit(chest);
            routes.setPortal(overworld);
            routes.setPortal(nether);
            WorldRoutes restored = new WorldRoutes(serverA);
            check(chest.equals(restored.deposit()), "Deposit coordinates retain their dimension across restart");
            check(overworld.equals(restored.portal(WorldRoutes.OVERWORLD)), "Keep source portal");
            check(nether.equals(restored.portal(WorldRoutes.NETHER)), "Store actual arrival rather than scaled coordinates");
            WorldRoutes otherServer = new WorldRoutes(temporary.resolve("server-b"));
            check(otherServer.deposit() == null && otherServer.portal(WorldRoutes.NETHER) == null, "Do not share endpoints between servers");
            restored.clearDeposit(WorldRoutes.NETHER);
            check(chest.equals(restored.deposit()), "Clearing a different dimension cannot erase the active chest");
            restored.clearPortal(WorldRoutes.NETHER);
            check(restored.portal(WorldRoutes.NETHER) == null && overworld.equals(restored.portal(WorldRoutes.OVERWORLD)), "Clear only the selected portal");
            check(chest.equals(restored.deposit()), "Portal edits cannot erase the deposit endpoint");
            restored.clearDeposit(WorldRoutes.OVERWORLD);
            check(new WorldRoutes(serverA).deposit() == null, "Deposit removal persists");
            Files.writeString(serverA.resolve("mining-routes.properties"), "deposit.dimension=minecraft:the_nether\ndeposit.x=not-a-number\n");
            check(new WorldRoutes(serverA).deposit() == null, "Malformed/incomplete endpoints fail closed");

            check(WorldRoutes.connects(WorldRoutes.OVERWORLD, WorldRoutes.NETHER), "Route into Nether");
            check(WorldRoutes.connects(WorldRoutes.NETHER, WorldRoutes.OVERWORLD), "Route back to Overworld");
            check(!WorldRoutes.connects(WorldRoutes.OVERWORLD, "minecraft:the_end"), "Reject unsupported portal type");
            check(!WorldRoutes.connects(WorldRoutes.NETHER, WorldRoutes.NETHER), "No dimension crossing for same-dimension travel");
            check(WorldRoutes.preserveTransition(true, true, 100, 200, WorldRoutes.NETHER, WorldRoutes.NETHER), "Preserve expected arrival");
            check(WorldRoutes.preserveTransition(true, true, 100, 200, WorldRoutes.NETHER, null), "Preserve transient world unload while connection remains alive");
            check(!WorldRoutes.preserveTransition(false, true, 100, 200, WorldRoutes.NETHER, null), "User stop/idle cannot preserve a job");
            check(!WorldRoutes.preserveTransition(true, false, 100, 200, WorldRoutes.NETHER, null), "Disconnect cancels even during a portal wait");
            check(!WorldRoutes.preserveTransition(true, true, 200, 200, WorldRoutes.NETHER, null), "Expired transition cannot resume later");
            check(!WorldRoutes.preserveTransition(true, true, 100, 200, WorldRoutes.NETHER, "minecraft:the_end"), "Unexpected dimension cancels");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}

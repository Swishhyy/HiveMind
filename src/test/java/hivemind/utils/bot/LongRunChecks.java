/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes;
import java.nio.file.*;
import java.util.*;

public final class LongRunChecks {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("hivemind-long-run");
        UUID bot = UUID.randomUUID(), other = UUID.randomUUID();
        var ow = new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD, 10, 63, 20);
        var owFar = new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD, 10000, 64, 10000);
        var nether = new WorldRoutes.Endpoint(WorldRoutes.NETHER, 2, 65, 3);
        var netherFar = new WorldRoutes.Endpoint(WorldRoutes.NETHER, 1200, 65, 1200);

        var stock = new StorageStockLedger(root, bot);
        stock.observe(ow, true, Map.of("minecraft:totem_of_undying", 27, "inside:minecraft:elytra", 3), 1000);
        check(new StorageStockLedger(root, other).recent().isEmpty(), "Private observations must not leak to another account");
        var reopened = new StorageStockLedger(root, bot).recent().get(0);
        check(reopened.privateStock() && reopened.items().get("inside:minecraft:elytra") == 3, "Persisted shulker stock");
        stock.observe(ow, true, Map.of("minecraft:totem_of_undying", 26), 32000);
        check(new StorageStockLedger(root, bot).recent().get(0).items().get("minecraft:totem_of_undying") == 26, "Observed stock changes");

        var kits = new TaskLoadoutStore(root, bot);
        kits.set(TaskLoadoutStore.Mode.HUNT, "crystals", 32);
        var hunt = new TaskLoadoutStore(root, bot).targets(TaskLoadoutStore.Mode.HUNT, Map.of("@food", 96, "@totems", 3), false, true, "auto", 128);
        check(hunt.get("@crystals") == 32 && hunt.get("@weapon") == 1 && hunt.get("@totems") == 4, "Persistent hunt kit");
        var overworld = kits.targets(TaskLoadoutStore.Mode.HUNT, Map.of(), false, false, "anchor", 128);
        check(!overworld.containsKey("@anchors") && !overworld.containsKey("@glowstone"), "No unsafe anchor request");
        kits.reset(TaskLoadoutStore.Mode.HUNT);
        check(new TaskLoadoutStore(root, bot).targets(TaskLoadoutStore.Mode.HUNT, Map.of(), false, true, "auto", 128).get("@crystals") == 16, "Reset kit");

        var journal = new ActivityJournal(root, bot);
        journal.note("Storage", "Chest empty"); journal.note("Storage", "Chest empty"); journal.note("Navigation", "Repath at 10,20");
        check(new ActivityJournal(root, bot).recent(10).size() == 2, "Deduplicated durable reasons");

        var links = new PortalLinks(root);
        links.record(ow, nether); links.record(owFar, netherFar);
        check(links.best(WorldRoutes.OVERWORLD, WorldRoutes.NETHER, owFar, netherFar).equals(owFar), "Choose observed arrival near goal");
        check(new PortalLinks(root).best(WorldRoutes.OVERWORLD, WorldRoutes.NETHER, ow, nether).equals(ow), "Persist actual portal pair");
        links.record(ow, netherFar);
        check(links.recent().size() == 2, "Update changed exit for same entrance");
        links.forgetDimension(WorldRoutes.NETHER);
        check(new PortalLinks(root).recent().isEmpty(), "Clear related links");
        System.out.println("Long-run stock, kits, journal and observed portal routes passed");
    }
}

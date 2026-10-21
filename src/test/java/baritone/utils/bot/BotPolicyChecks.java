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

package baritone.utils.bot;

import baritone.utils.mining.WorldRoutes;
import java.nio.file.*;
import java.util.*;

/** Checks the boundaries that could lose inventory, trust the wrong sender, or repeat a completed job. */
public final class BotPolicyChecks {
    public static void main(String[] args) throws Exception { runAll(); System.out.println("Bot policy checks passed: trust, storage rules, jobs, survival, sculk"); }
    public static void runAll() throws Exception {
        Path temp = Files.createTempDirectory("baritone-bot-checks-");
        try {
            UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
            TrustedControl control = new TrustedControl(temp);
            check(!control.trusted(owner), "Empty allowlist denies control");
            control.change(owner, true);
            check(new TrustedControl(temp).trusted(owner) && !control.trusted(stranger), "Trust persists as exact UUIDs");
            check(control.whisperType("minecraft:msg_command_incoming") && !control.whisperType("minecraft:chat"), "Public chat cannot enter whisper control");
            check(control.parseServerWhisper("Dylan whispers to you: #stop") == null, "Unsigned text is ignored by default");
            check(TrustedControl.command("#mine 32 netherite").equals("mine 32 netherite"), "Commands require explicit prefix");
            for (String bad : List.of("mine 32 netherite", "#trust add stranger", "#set allowBreak true", "#mine 1 stone\n#stop", "#build house", "#" + "x".repeat(221)))
                check(TrustedControl.command(bad) == null, "Reject non-command or administrative remote text: " + bad);
            Properties config = AtomicProperties.read(temp.resolve("bot-control.properties"));
            config.setProperty("allowUnsignedServerWhispers", "true"); config.setProperty("unsignedWhisperFormat", "[{sender} -> me] {command}");
            AtomicProperties.write(temp.resolve("bot-control.properties"), config); control.reload();
            var parsed = control.parseServerWhisper("[Dylan -> me] #job status");
            check(parsed != null && parsed.getKey().equals("Dylan") && parsed.getValue().equals("#job status"), "Exact optional server format parses bounded names and commands");
            check(control.parseServerWhisper("<stranger> [Dylan -> me] #stop") == null, "Embedded imitation is not an exact server whisper");
            check(control.parseServerWhisper("[Dylan:admin -> me] #stop") == null, "Display-name styling cannot become a username");
            control.change(owner, false); check(!new TrustedControl(temp).trusted(owner), "Revoking trust persists");

            StorageRule ores = new StorageRule("Ores", StorageRule.Kind.ORES, Set.of(), 10);
            check(ores.accepts("minecraft:ancient_debris", true, false, false) && ores.accepts("minecraft:diamond", false, false, false), "Ore chest accepts debris and minerals");
            check(!ores.accepts("minecraft:cobblestone", true, true, false) && !ores.accepts("mod:diamond", false, false, false), "Ore heuristics don't claim unrelated/modded IDs");
            StorageRule custom = new StorageRule("Custom", StorageRule.Kind.ITEMS, Set.of("mod:ore"), 200);
            check(custom.priority() == 100 && custom.accepts("mod:ore", false, false, false) && !custom.accepts("minecraft:diamond", false, false, false), "Explicit allowlists match exact IDs");
            WorldRoutes routes = new WorldRoutes(temp); int id = routes.addDeposit(new WorldRoutes.Endpoint(WorldRoutes.OVERWORLD, 1, 64, 2)).id();
            routes.configureStorage(id, ores); check(new WorldRoutes(temp).deposits().get(0).rule().equals(ores), "Storage labels, roles, and priorities survive restart");
            routes.removeDeposit(id); check(!Files.readString(temp.resolve("mining-routes.properties")).contains("storage." + id + ".kind"), "Removal deletes its rule too");
            int newId = routes.addDeposit(new WorldRoutes.Endpoint(WorldRoutes.NETHER, 1, 64, 2)).id();
            check(newId > id && routes.deposits().get(0).rule().equals(StorageRule.DEFAULT), "New storage cannot inherit a removed chest's rules");
            Properties malformed = AtomicProperties.read(temp.resolve("mining-routes.properties")); malformed.setProperty("storage." + newId + ".kind", "typo");
            AtomicProperties.write(temp.resolve("mining-routes.properties"), malformed);
            check(new WorldRoutes(temp).deposits().get(0).rule().kind() == StorageRule.Kind.DISABLED, "Malformed storage filters fail closed");

            JobStore jobs = new JobStore(temp);
            var job = new JobStore.Job(1, 32, List.of("minecraft:ancient_debris"), 12, owner);
            jobs.write(List.of(job)); check(jobs.read().equals(List.of(job)), "Queue preserves target, progress credit, and controller across restart");
            jobs.write(List.of(job.withDeposited(32))); check(jobs.read().get(0).deposited() == 32, "Reached goals retain credit across a restart during the final deposit");
            check(new JobStore(temp.resolve("other-server")).read().isEmpty(), "Jobs are isolated by server/save");
            boolean invalid = false; try { new JobStore.Job(2, -1, List.of("minecraft:stone"), 0, null); } catch (IllegalArgumentException expected) { invalid = true; }
            check(invalid, "Reject unbounded/negative finite jobs");
            jobs.write(List.of()); check(jobs.read().isEmpty(), "Cancellation persists an empty queue");

            check(!SurvivalPolicy.usableTool(32, 32) && SurvivalPolicy.usableTool(33, 32), "Stop before reserve durability is used");
            check(SurvivalPolicy.shouldEat(16, 20, 20, 16) && !SurvivalPolicy.shouldEat(20, 10, 20, 16), "Eat for hunger or recovery without trying to eat when full");
            check(!SurvivalPolicy.mayDefend(12, 20, 1, false, false, true), "Low health forces retreat");
            check(!SurvivalPolicy.mayDefend(20, 20, 2, false, false, true), "Outnumbered bots retreat");
            check(!SurvivalPolicy.mayDefend(20, 20, 1, true, false, true) && !SurvivalPolicy.mayDefend(20, 20, 1, false, true, true), "Wardens and creepers are not melee targets");
            check(!SurvivalPolicy.mayDefend(20, 20, 1, false, false, false) && SurvivalPolicy.mayDefend(20, 20, 1, false, false, true), "Only a confirmed attacker permits retaliation");
            var zone = new SculkPolicy.Zone(-32, 64, -32, 12);
            var snapshot = new SculkPolicy.Snapshot(List.of(zone));
            check(snapshot.contains(-20, 64, -32) && !snapshot.contains(-19, 64, -32), "Hazard index respects exact radius across negative cell boundaries");
            check(new SculkPolicy.Snapshot(List.of(new SculkPolicy.Zone(31, 64, 31, 20))).contains(51, 64, 31), "Calibrated sensor buffer spans index cells");
            check(!snapshot.contains(-32, 77, -32), "Vertical separation is included");
            check(snapshot.penalty(-32, 64, -32) > snapshot.penalty(-21, 64, -32), "Path costs prefer leaving the sensor zone");
            check(SculkPolicy.sculk("calibrated_sculk_sensor") && SculkPolicy.sculk("sculk_vein") && !SculkPolicy.sculk("stone"), "Protect all sculk blocks, including calibrated sensors");
        } finally { try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

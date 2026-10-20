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

import java.util.HashSet;
import java.util.Set;

/** Dependency-free regression checks; also called from the normal JUnit suite. */
public final class MiningPolicyChecks {
    private MiningPolicyChecks() {}

    public static void main(String[] args) {
        runAll();
        System.out.println("Mining policy checks passed: reserves, slot layouts, transfers, materials, protection boundaries");
    }

    public static void runAll() {
        check(DepositPolicy.retain(0, false, true, true, false, false), "Keep the hotbar");
        check(DepositPolicy.retain(15, false, true, true, true, false), "Keep supplies outside hotbar");
        check(DepositPolicy.retain(30, false, false, false, false, true), "Keep explicitly reserved items");
        check(!DepositPolicy.retain(15, false, true, true, false, false), "Deposit ordinary mining output");
        check(!DepositPolicy.retain(0, true, true, true, true, true), "Explicit all overrides every main-inventory reserve");
        for (int storage : new int[]{27, 54}) {
            Set<Integer> mapped = new HashSet<>();
            for (int inventory = 0; inventory < 36; inventory++) {
                int slot = DepositPolicy.menuSlot(inventory, storage);
                check(slot >= storage && slot < storage + 36, "Never shift-click a storage slot");
                check(mapped.add(slot), "Every source inventory slot must have a unique menu slot");
            }
            check(DepositPolicy.menuSlot(9, storage) == storage, "First main-inventory slot");
            check(DepositPolicy.menuSlot(35, storage) == storage + 26, "Last main-inventory slot");
            check(DepositPolicy.menuSlot(0, storage) == storage + 27, "First hotbar slot");
            check(DepositPolicy.menuSlot(8, storage) == storage + 35, "Last hotbar slot");
        }
        expectInvalid(() -> DepositPolicy.menuSlot(-1, 27));
        expectInvalid(() -> DepositPolicy.menuSlot(36, 27));
        expectInvalid(() -> DepositPolicy.menuSlot(0, 0));
        expectInvalid(() -> DepositPolicy.menuSlot(0, 28));
        check(DepositPolicy.transferred(64, 0) == 64, "Whole-stack transfer");
        check(DepositPolicy.transferred(64, 50) == 14, "Partial chest capacity");
        check(DepositPolicy.transferred(64, 64) == 0, "Rejected transfer is not counted");
        check(DepositPolicy.transferred(64, 70) == 0, "Concurrent pickups cannot create negative deposits");
        for (String block : new String[]{"oak_planks", "stone_bricks", "cobblestone", "glass", "blue_stained_glass_pane",
                "polished_andesite_stairs", "white_concrete", "red_wool", "spruce_trapdoor", "andesite_casing"}) {
            check(ConstructionPolicy.isConstruction(block), "Recognize likely building material: " + block);
        }
        for (String block : new String[]{"stone", "deepslate", "dirt", "granite", "diamond_ore", "redstone_ore", "oak_log"}) {
            check(!ConstructionPolicy.isConstruction(block), "Do not treat common natural terrain as known construction: " + block);
        }
        for (String block : new String[]{"chest", "barrel", "furnace", "hopper", "red_shulker_box"}) {
            check(ConstructionPolicy.isContainer(block), "Protect storage: " + block);
        }
        ConstructionPolicy.Area base = new ConstructionPolicy.Area(-100, -40, 100, 16);
        check(base.contains(-84, -24, 116), "Include the protection boundary");
        check(!base.contains(-83, -24, 116), "Do not expand beyond the boundary");
        check(new ConstructionPolicy.Area(0, 0, 0, 0).contains(0, 0, 0), "Protect a single designated block");
        check(!new ConstructionPolicy.Area(Integer.MIN_VALUE, 0, 0, 16).contains(Integer.MAX_VALUE, 0, 0), "Coordinate overflow must not match a region");
        expectInvalid(() -> new ConstructionPolicy.Area(0, 0, 0, -1));
        expectInvalid(() -> new ConstructionPolicy.Area(0, 0, 0, 257));
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void expectInvalid(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected invalid input to be rejected");
    }
}

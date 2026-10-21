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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Disposal is irreversible in game; exercise retained-resource and inventory-slot invariants without a client. */
public final class WastePolicyChecks {
    private WastePolicyChecks() {}

    public static void main(String[] args) {
        runAll();
        System.out.println("Waste checks passed: building reserves, protected resources, retries, inventory boundaries");
    }

    public static void runAll() {
        List<WastePolicy.Stack> mixed = List.of(
                new WastePolicy.Stack(0, 64, true, false),
                new WastePolicy.Stack(9, 64, true, false),
                new WastePolicy.Stack(10, 64, true, false),
                new WastePolicy.Stack(11, 63, true, false),
                new WastePolicy.Stack(12, 64, false, true),
                new WastePolicy.Stack(13, 64, true, true));
        check(WastePolicy.buildingSlots(mixed, 2).equals(Set.of(0, 9)), "Two stacks total; prefer an equally full hotbar stack");
        check(WastePolicy.nextDiscard(mixed, 2, Set.of()).orElseThrow() == 10, "Drop excess main-inventory stacks first");
        check(WastePolicy.nextDiscard(mixed, 2, Set.of(10)).orElseThrow() == 11, "Never retry an attempted slot in one pass");
        check(WastePolicy.nextDiscard(mixed, 2, Set.of(10, 11)).isEmpty(), "Targets and reserved/custom resources cannot be dropped");
        check(WastePolicy.buildingSlots(List.of(new WastePolicy.Stack(0, 1, true, false),
                new WastePolicy.Stack(9, 64, true, false), new WastePolicy.Stack(10, 63, true, false)), 2)
                .equals(Set.of(9, 10)), "Keep the fullest stacks even when smaller stacks occupy the hotbar");
        check(WastePolicy.nextDiscard(mixed, 36, Set.of()).isEmpty(), "A large reserve retains all usable stacks");
        check(WastePolicy.buildingSlots(mixed, -1).isEmpty(), "Negative configuration is bounded to zero");
        check(WastePolicy.nextDiscard(List.of(new WastePolicy.Stack(20, 64, false, true)), 0, Set.of()).isEmpty(),
                "A zero building reserve never overrides resource protection");

        Set<Integer> menuSlots = new HashSet<>();
        for (int slot = 0; slot < 36; slot++) {
            int menuSlot = WastePolicy.menuSlot(slot);
            check(menuSlot >= 9 && menuSlot <= 44, "Never click crafting, armor, offhand, or outside the player inventory");
            check(menuSlots.add(menuSlot), "Each inventory stack has a distinct menu slot");
        }
        check(WastePolicy.menuSlot(0) == 36 && WastePolicy.menuSlot(8) == 44, "Hotbar drop mapping");
        check(WastePolicy.menuSlot(9) == 9 && WastePolicy.menuSlot(35) == 35, "Main-inventory drop mapping");
        expectInvalid(() -> WastePolicy.menuSlot(-1));
        expectInvalid(() -> WastePolicy.menuSlot(36));
        expectInvalid(() -> new WastePolicy.Stack(36, 64, true, false));
        expectInvalid(() -> new WastePolicy.Stack(0, 0, true, false));

        Random random = new Random(263);
        for (int trial = 0; trial < 200; trial++) {
            List<WastePolicy.Stack> inventory = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                inventory.add(new WastePolicy.Stack(slot, 1 + random.nextInt(64), random.nextBoolean(), random.nextBoolean()));
            }
            Set<Integer> reserve = WastePolicy.buildingSlots(inventory, 2);
            Set<Integer> attempted = new HashSet<>();
            while (true) {
                var next = WastePolicy.nextDiscard(inventory, 2, attempted);
                if (next.isEmpty()) break;
                int slot = next.getAsInt();
                check(!reserve.contains(slot), "Repeated disposal preserves the original two building stacks");
                WastePolicy.Stack stack = inventory.stream().filter(item -> item.slot() == slot).findFirst().orElseThrow();
                check(!stack.protectedItem(), "No protected stack can enter a drop plan");
                check(attempted.add(slot), "Each slot is attempted only once");
                inventory.remove(stack);
            }
            check(inventory.stream().filter(stack -> stack.building() && !stack.protectedItem()).count() == reserve.size(),
                    "Disposal leaves exactly the available building reserve");
        }
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

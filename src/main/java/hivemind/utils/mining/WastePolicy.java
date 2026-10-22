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

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/** Plans whole-stack disposal without ever using crafting, armor, offhand, or cursor slots. */
public final class WastePolicy {
    private WastePolicy() {}

    public record Stack(int slot, int count, boolean building, boolean protectedItem) {
        public Stack {
            if (slot < 0 || slot >= 36 || count <= 0) {
                throw new IllegalArgumentException("Invalid waste inventory stack");
            }
        }
    }

    /** Keep the fullest usable stacks, preferring hotbar stacks when equally full. */
    public static Set<Integer> buildingSlots(List<Stack> stacks, int keepStacks) {
        return stacks.stream().filter(stack -> stack.building() && !stack.protectedItem())
                .sorted(Comparator.comparingInt(Stack::count).reversed()
                        .thenComparingInt(stack -> stack.slot() < 9 ? 0 : 1)
                        .thenComparingInt(Stack::slot))
                .limit(Math.max(0, Math.min(36, keepStacks)))
                .map(Stack::slot).collect(Collectors.toSet());
    }

    /** A rejected or replenished slot is never clicked twice during the same cleanup pass. */
    public static OptionalInt nextDiscard(List<Stack> stacks, int keepStacks, Set<Integer> attempted) {
        Set<Integer> kept = buildingSlots(stacks, keepStacks);
        return stacks.stream().filter(stack -> !stack.protectedItem() && !kept.contains(stack.slot())
                        && !attempted.contains(stack.slot()))
                .sorted(Comparator.comparingInt((Stack stack) -> stack.slot() < 9 ? 1 : 0)
                        .thenComparingInt(Stack::slot))
                .mapToInt(Stack::slot).findFirst();
    }

    public static int menuSlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) {
            throw new IllegalArgumentException("Invalid player inventory slot");
        }
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }
}

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

/** Inventory rules kept independent of Minecraft so transfer safety can be checked without a game client. */
public final class DepositPolicy {
    private DepositPolicy() {}

    public static boolean retain(int inventorySlot, boolean all, boolean keepHotbar,
                                 boolean keepSupplies, boolean supply, boolean explicitlyKept) {
        return retain(inventorySlot, all, keepHotbar, keepSupplies, supply, explicitlyKept, false);
    }

    public static boolean retain(int inventorySlot, boolean all, boolean keepHotbar,
                                 boolean keepSupplies, boolean supply, boolean explicitlyKept, boolean miningOutput) {
        return !all && ((keepHotbar && !miningOutput && inventorySlot < 9) || (keepSupplies && supply) || explicitlyKept);
    }

    /** Vanilla chest menus contain storage slots, then inventory slots 9..35, then hotbar slots 0..8. */
    public static int menuSlot(int inventorySlot, int storageSlots) {
        if (inventorySlot < 0 || inventorySlot >= 36 || storageSlots < 9 || storageSlots % 9 != 0) {
            throw new IllegalArgumentException("Invalid chest/inventory slot layout");
        }
        return storageSlots + (inventorySlot < 9 ? 27 + inventorySlot : inventorySlot - 9);
    }

    public static int transferred(int before, int remaining) {
        return Math.max(0, Math.min(before, before - remaining));
    }
}

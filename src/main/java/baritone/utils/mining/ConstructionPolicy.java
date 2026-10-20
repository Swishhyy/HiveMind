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

import java.util.Set;

/** Pure material heuristics. Minecraft does not transmit a block's placement history. */
public final class ConstructionPolicy {
    private static final Set<String> CONTAINERS = Set.of(
            "chest", "trapped_chest", "copper_chest", "ender_chest", "barrel", "hopper", "furnace",
            "blast_furnace", "smoker", "dispenser", "dropper", "brewing_stand", "shulker_box");
    private static final Set<String> MATERIALS = Set.of(
            "cobblestone", "mossy_cobblestone", "smooth_stone", "bricks", "stone_bricks",
            "glass", "glass_pane", "terracotta", "ladder", "scaffolding", "bookshelf",
            "chiseled_bookshelf", "crafting_table", "torch", "wall_torch", "lantern",
            "soul_lantern", "redstone_wire", "redstone_torch", "redstone_wall_torch",
            "repeater", "comparator", "lever", "piston", "sticky_piston", "observer",
            "iron_block", "gold_block", "diamond_block", "emerald_block", "netherite_block");
    private static final String[] SUFFIXES = {
            "_planks", "_bricks", "_stairs", "_slab", "_wall", "_glass", "_glass_pane",
            "_terracotta", "_concrete", "_concrete_powder", "_wool", "_carpet", "_door",
            "_trapdoor", "_fence", "_fence_gate", "_bed", "_lantern", "_casing", "_sign"
    };

    private ConstructionPolicy() {}

    public static boolean isContainer(String path) {
        return CONTAINERS.contains(path) || path.endsWith("_shulker_box") || path.endsWith("_copper_chest");
    }

    public static boolean isConstruction(String path) {
        if (isContainer(path) || MATERIALS.contains(path)) {
            return true;
        }
        for (String suffix : SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public record Area(int x, int y, int z, int radius) {
        public Area {
            if (radius < 0 || radius > 256) {
                throw new IllegalArgumentException("Protection radius must be between 0 and 256");
            }
        }

        public boolean contains(int bx, int by, int bz) {
            return Math.abs((long) bx - x) <= radius
                    && Math.abs((long) by - y) <= radius
                    && Math.abs((long) bz - z) <= radius;
        }
    }
}

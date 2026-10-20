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

import java.util.Locale;
import java.util.Set;

/** Item categories operate on registry IDs, without loading a game or trusting display names. */
public record StorageRule(String label, Kind kind, Set<String> items, int priority) {
    public enum Kind { ANY, ORES, BUILDING, JUNK, SUPPLIES, ITEMS, DISABLED }
    public static final StorageRule DEFAULT = new StorageRule("", Kind.ANY, Set.of(), 0);
    public StorageRule {
        if (label == null || label.length() > 40 || label.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Storage labels must be at most 40 printable characters");
        if (kind == null || items == null || items.size() > 128) throw new IllegalArgumentException("Invalid storage rule");
        items = Set.copyOf(items);
        if (items.stream().anyMatch(item -> !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))
            throw new IllegalArgumentException("Use full item registry IDs");
        priority = Math.max(-100, Math.min(100, priority));
    }
    public boolean accepts(String item, boolean building, boolean waste, boolean supply) {
        return switch (kind) {
            case ANY -> true;
            case ITEMS -> items.contains(item);
            case BUILDING -> building && !ore(item);
            case JUNK -> waste;
            case SUPPLIES -> supply;
            case ORES -> ore(item);
            case DISABLED -> false;
        };
    }
    private static boolean ore(String item) {
        if (!item.startsWith("minecraft:")) return false;
        String path = item.substring(10);
        return path.endsWith("_ore") || path.startsWith("raw_") || path.endsWith("_ingot")
                || path.endsWith("_nugget") || Set.of("diamond", "emerald", "coal", "redstone", "lapis_lazuli",
                "quartz", "ancient_debris", "netherite_scrap", "amethyst_shard").contains(path);
    }
    public static Kind parse(String value) { return Kind.valueOf(value.toUpperCase(Locale.ROOT)); }
}

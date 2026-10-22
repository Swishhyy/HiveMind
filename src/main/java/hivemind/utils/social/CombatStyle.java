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

package hivemind.utils.social;

import java.util.Locale;

public enum CombatStyle {
    AUTO, MELEE, CRYSTAL, ANCHOR;
    public static CombatStyle parse(String value) {
        try { return valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch(RuntimeException error) { throw new IllegalArgumentException("Combat style must be auto, melee, crystal, or anchor"); }
    }
}

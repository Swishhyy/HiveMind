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

package hivemind.utils.bot;

/** All health values are health points (two points per heart). */
public final class SurvivalPolicy {
    private SurvivalPolicy() {}
    public static int bounded(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    public static boolean usableTool(int remaining, int reserve) { return remaining > bounded(reserve, 1, 1000); }
    public static boolean shouldEat(int hunger, float health, float maxHealth, int threshold) {
        return hunger < 20 && (hunger <= bounded(threshold, 1, 19) || health < maxHealth);
    }
    public static boolean mayDefend(float health, int hunger, int nearby, boolean warden, boolean creeper, boolean actualAttacker) {
        return actualAttacker && health > 12 && hunger > 6 && nearby <= 1 && !warden && !creeper;
    }
}

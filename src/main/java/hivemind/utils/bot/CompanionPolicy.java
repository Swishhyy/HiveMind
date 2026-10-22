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

public final class CompanionPolicy {
    private CompanionPolicy() {}
    public static int quantity(int amount){if(amount<1||amount>4096)throw new IllegalArgumentException("Quantity must be 1-4096");return amount;}
    public static int remaining(int requested,int confirmed){return Math.max(0,requested-Math.max(0,confirmed));}
    public static boolean mayHandoff(boolean samePlayer,boolean inRange,boolean lineOfSight,boolean alone,boolean stationary){return samePlayer&&inRange&&lineOfSight&&alone&&stationary;}
    public static int dropButton(int stack,int remaining){if(stack<1||remaining<1)throw new IllegalArgumentException("Nothing to hand off");return remaining>=stack?1:0;}
    public static boolean repairComplete(int remaining,int maximum,int percent){return maximum>0&&remaining>=Math.ceil(maximum*Math.max(1,Math.min(100,percent))/100D);}
}

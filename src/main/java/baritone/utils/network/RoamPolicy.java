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

package baritone.utils.network;

/** Small, testable autonomy rules keep manual stops and care above background scouting. */
public final class RoamPolicy {
    private RoamPolicy() {}
    public static boolean eligible(boolean enabled,boolean held,boolean alive,boolean foreground,boolean queued,boolean screen){return enabled&&!held&&alive&&!foreground&&!queued&&!screen;}
    public static double frontierScore(boolean visited,double distance,double headingDifference,boolean occupied){return (visited?10000:0)+distance*.1+Math.abs(headingDifference)*20+(occupied?20000:0);}
    public static boolean needsCare(float health,int hunger){return health<14||hunger<=12;}
}

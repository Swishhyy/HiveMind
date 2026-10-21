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

public final class AutonomyPolicy {
    private AutonomyPolicy(){}
    public static long reportInterval(long configured){return Math.max(30000,Math.min(3600000,configured));}
    public static long respawnDelay(long configured){return Math.max(1000,Math.min(30000,configured));}
    public static boolean mayReport(boolean trusted,boolean online,long now,long queued){return trusted&&online&&now-queued<=180000&&queued<=now;}
    public static boolean logoutEvidence(boolean wasOnline,boolean online,boolean connected,long seenAt,long now){return connected&&wasOnline&&!online&&seenAt>0&&now>=seenAt&&now-seenAt<=120000;}
    public static boolean mayFly(boolean enabled,boolean loaded,boolean equipped,int durability,int rockets,int reserve,double distance,int minimum,boolean closeTarget,boolean busy){
        return enabled&&loaded&&equipped&&durability>=32&&rockets>=Math.max(2,reserve)+2&&distance>=Math.max(96,minimum)&&!closeTarget&&!busy;
    }
}

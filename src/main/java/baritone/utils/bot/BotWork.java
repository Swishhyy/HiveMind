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

import baritone.Baritone;

public final class BotWork {
    private BotWork() {}
    public static boolean active(Baritone bot) {
        return bot.getMineProcess().isActive() || bot.getDepositProcess().isActive()
                || bot.getStalkProcess().isActive() || bot.getFollowProcess().isActive()
                || bot.getCustomGoalProcess().isActive() || bot.getBuilderProcess().isActive()
                || bot.getExploreProcess().isActive() || bot.getFarmProcess().isActive()
                || bot.getGetToBlockProcess().isActive() || bot.getSurvivalProcess().isActive()
                || bot.getCompanionProcess().isActive() || bot.getWorkstationProcess().isActive()
                || bot.getRepairProcess().isActive();
    }
}

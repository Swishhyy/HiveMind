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

import hivemind.HiveMind;

public final class BotWork {
    private BotWork() {}
    public static boolean active(HiveMind bot) {
        return foreground(bot)||bot.getRoamProcess().isActive();
    }
    public static boolean foreground(HiveMind bot){
        return bot.getMineProcess().isActive() || bot.getDepositProcess().isActive()
                || bot.getStalkProcess().isActive() || bot.getFollowProcess().isActive()
                || bot.getCustomGoalProcess().isActive() || bot.getBuilderProcess().isActive()
                || bot.getExploreProcess().isActive() || bot.getFarmProcess().isActive()
                || bot.getGetToBlockProcess().isActive() || bot.getSurvivalProcess().isActive()
                || bot.getCompanionProcess().isActive() || bot.getWorkstationProcess().isActive()
                || bot.getRepairProcess().isActive() || bot.getShulkerProcess().isActive();
    }
}

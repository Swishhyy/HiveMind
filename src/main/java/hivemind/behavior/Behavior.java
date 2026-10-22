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

package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.behavior.IBehavior;
import hivemind.api.utils.IPlayerContext;

/**
 * A type of game event listener that is given {@link HiveMind} instance context.
 *
 * @author Brady
 * @since 8/1/2018
 */
public class Behavior implements IBehavior {

    public final HiveMind hivemind;
    public final IPlayerContext ctx;

    protected Behavior(HiveMind hivemind) {
        this.hivemind = hivemind;
        this.ctx = hivemind.getPlayerContext();
    }
}

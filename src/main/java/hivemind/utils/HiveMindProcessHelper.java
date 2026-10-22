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

package hivemind.utils;

import hivemind.HiveMind;
import hivemind.api.process.IHiveMindProcess;
import hivemind.api.utils.Helper;
import hivemind.api.utils.IPlayerContext;

public abstract class HiveMindProcessHelper implements IHiveMindProcess, Helper {

    protected final HiveMind hivemind;
    protected final IPlayerContext ctx;

    public HiveMindProcessHelper(HiveMind hivemind) {
        this.hivemind = hivemind;
        this.ctx = hivemind.getPlayerContext();
    }

    @Override
    public boolean isTemporary() {
        return false;
    }
}

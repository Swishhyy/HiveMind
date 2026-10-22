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

package hivemind.api.process;

import hivemind.api.pathing.goals.Goal;
import hivemind.api.utils.BetterBlockPos;
import net.minecraft.core.BlockPos;

import java.util.List;

public interface IElytraProcess extends IHiveMindProcess {

    void repackChunks();

    /**
     * @return Where it is currently flying to, null if not active
     */
    BlockPos currentDestination();

    /**
     * @return Current active path, empty if not active or no path has been calculated yet
     */
    List<BetterBlockPos> getPath();

    void pathTo(BlockPos destination);

    void pathTo(Goal destination);

    /**
     * Resets the state of the process but will maintain the same destination and will try to keep flying
     */
    void resetState();

    /**
     * @return {@code true} if the native library loaded and elytra is actually usable
     */
    boolean isLoaded();

    /*
     * FOR INTERNAL USE ONLY. MAY BE REMOVED AT ANY TIME.
     */
    boolean isSafeToCancel();
}

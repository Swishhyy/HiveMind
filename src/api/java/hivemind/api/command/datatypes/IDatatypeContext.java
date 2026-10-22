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

package hivemind.api.command.datatypes;

import hivemind.api.IHiveMind;
import hivemind.api.command.argument.IArgConsumer;

/**
 * Provides an {@link IDatatype} with contextual information so
 * that it can perform the desired operation on the target level.
 *
 * @author Brady
 * @see IDatatype
 * @since 9/26/2019
 */
public interface IDatatypeContext {

    /**
     * Provides the {@link IHiveMind} instance that is associated with the action relating to datatype handling.
     *
     * @return The context {@link IHiveMind} instance.
     */
    IHiveMind getHiveMind();

    /**
     * Provides the {@link IArgConsumer}} to fetch input information from.
     *
     * @return The context {@link IArgConsumer}}.
     */
    IArgConsumer getConsumer();
}

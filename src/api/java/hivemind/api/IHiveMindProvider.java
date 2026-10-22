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

package hivemind.api;

import hivemind.api.cache.IWorldScanner;
import hivemind.api.command.ICommand;
import hivemind.api.command.ICommandSystem;
import hivemind.api.schematic.ISchematicSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.Objects;

/**
 * Provides the present {@link IHiveMind} instances, as well as non-hivemind instance related APIs.
 *
 * @author leijurv
 */
public interface IHiveMindProvider {

    /**
     * Returns the primary {@link IHiveMind} instance. This instance is persistent, and
     * is represented by the local player that is created by the game itself, not a "bot"
     * player through HiveMind.
     *
     * @return The primary {@link IHiveMind} instance.
     */
    IHiveMind getPrimaryHiveMind();

    /**
     * Returns all of the active {@link IHiveMind} instances. This includes the local one
     * returned by {@link #getPrimaryHiveMind()}.
     *
     * @return All active {@link IHiveMind} instances.
     * @see #getHiveMindForPlayer(LocalPlayer)
     */
    List<IHiveMind> getAllHiveMinds();

    /**
     * Provides the {@link IHiveMind} instance for a given {@link LocalPlayer}.
     *
     * @param player The player
     * @return The {@link IHiveMind} instance.
     */
    default IHiveMind getHiveMindForPlayer(LocalPlayer player) {
        for (IHiveMind hivemind : this.getAllHiveMinds()) {
            if (Objects.equals(player, hivemind.getPlayerContext().player())) {
                return hivemind;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IHiveMind} instance for a given {@link Minecraft}.
     *
     * @param minecraft The minecraft
     * @return The {@link IHiveMind} instance.
     */
    default IHiveMind getHiveMindForMinecraft(Minecraft minecraft) {
        for (IHiveMind hivemind : this.getAllHiveMinds()) {
            if (Objects.equals(minecraft, hivemind.getPlayerContext().minecraft())) {
                return hivemind;
            }
        }
        return null;
    }

    /**
     * Provides the {@link IHiveMind} instance for the player with the specified connection.
     *
     * @param connection The connection
     * @return The {@link IHiveMind} instance.
     */
    default IHiveMind getHiveMindForConnection(ClientPacketListener connection) {
        for (IHiveMind hivemind : this.getAllHiveMinds()) {
            final LocalPlayer player = hivemind.getPlayerContext().player();
            if (player != null && player.connection == connection) {
                return hivemind;
            }
        }
        return null;
    }

    /**
     * Creates and registers a new {@link IHiveMind} instance using the specified {@link Minecraft}. The existing
     * instance is returned if already registered.
     *
     * @param minecraft The minecraft
     * @return The {@link IHiveMind} instance
     */
    IHiveMind createHiveMind(Minecraft minecraft);

    /**
     * Destroys and removes the specified {@link IHiveMind} instance. If the specified instance is the
     * {@link #getPrimaryHiveMind() primary hivemind}, this operation has no effect and will return {@code false}.
     *
     * @param hivemind The hivemind instance to remove
     * @return Whether the hivemind instance was removed
     */
    boolean destroyHiveMind(IHiveMind hivemind);

    /**
     * Returns the {@link IWorldScanner} instance. This is not a type returned by
     * {@link IHiveMind} implementation, because it is not linked with {@link IHiveMind}.
     *
     * @return The {@link IWorldScanner} instance.
     */
    IWorldScanner getWorldScanner();

    /**
     * Returns the {@link ICommandSystem} instance. This is not bound to a specific {@link IHiveMind}
     * instance because {@link ICommandSystem} itself controls global behavior for {@link ICommand}s.
     *
     * @return The {@link ICommandSystem} instance.
     */
    ICommandSystem getCommandSystem();

    /**
     * @return The {@link ISchematicSystem} instance.
     */
    ISchematicSystem getSchematicSystem();
}

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
package hivemind.utils.network;

import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.Properties;

/** Created during client initialization, before a world or a network command is needed. */
public final class ClientNetworkKey {
    private final String key;
    public ClientNetworkKey(Path directory) {
        Path file = directory.resolve("bot-client.properties");
        Properties values = AtomicProperties.read(file);
        String stored = values.getProperty("networkKey", "");
        if (stored.isEmpty()) {
            stored = NetworkProtocol.newKey(); values.setProperty("networkKey", stored);
            AtomicProperties.write(file, values);
        }
        key = NetworkProtocol.checkKey(stored);
    }
    public String key() { return key; }
}

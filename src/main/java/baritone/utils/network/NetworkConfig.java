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

import baritone.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;

/** Local per-Minecraft-server transport configuration, intentionally outside the whisper command allowlist. */
public final class NetworkConfig {
    public static final int DEFAULT_PORT = 28766;
    public enum Mode { OFF, HOST, JOIN }
    public record Options(Mode mode, String key, String host, int port, String bind, String scope, int webPort) {
        public Options {
            Objects.requireNonNull(mode);
            if (key == null || (!key.isEmpty() && !NetworkProtocol.checkKey(key).equals(key))) throw new IllegalArgumentException("Invalid network key");
            address(host); address(bind);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Network port must be 1-65535");
            if (webPort < 0 || webPort > 65535) throw new IllegalArgumentException("Web port must be 1-65535, or zero to disable");
            if (scope == null || !scope.matches("[A-Za-z0-9_.:-]{0,128}")) throw new IllegalArgumentException("Scope must be a short server label without spaces");
            if (mode != Mode.OFF && key.isEmpty()) throw new IllegalArgumentException("Set network key before hosting or joining");
        }
        private static void address(String value) {
            if (value == null || value.isEmpty() || value.length() > 253 || !value.matches("[A-Za-z0-9_.:%-]+"))
                throw new IllegalArgumentException("Use a host name or IP address; supply the port separately");
        }
        public Options mode(Mode next, String host, int port) { return new Options(next, key, host, port, bind, scope, webPort); }
        public Options key(String value) { return new Options(mode, NetworkProtocol.checkKey(value), host, port, bind, scope, webPort); }
        public Options bind(String value) { return new Options(mode, key, host, port, value, scope, webPort); }
        public Options scope(String value) { return new Options(mode, key, host, port, bind, value, webPort); }
        public Options web(int value) { return new Options(mode, key, host, port, bind, scope, value); }
    }
    private final Path file;
    private Options options;
    public NetworkConfig(Path root) {
        file = root.resolve("bot-network.properties"); Properties p = AtomicProperties.read(file);
        try {
            options = new Options(Mode.valueOf(p.getProperty("mode", "OFF").toUpperCase(Locale.ROOT)), p.getProperty("key", ""),
                    p.getProperty("host", "127.0.0.1"), Integer.parseInt(p.getProperty("port", "" + DEFAULT_PORT)),
                    p.getProperty("bind", "0.0.0.0"), p.getProperty("scope", ""), Integer.parseInt(p.getProperty("webPort", "0")));
        } catch (RuntimeException e) { throw new IllegalStateException("Invalid bot-network.properties; correct it before connecting", e); }
    }
    public Options options() { return options; }
    public void save(Options next) {
        Properties p = new Properties();
        p.setProperty("mode", next.mode().name()); p.setProperty("key", next.key()); p.setProperty("host", next.host());
        p.setProperty("port", "" + next.port()); p.setProperty("bind", next.bind()); p.setProperty("scope", next.scope());
        p.setProperty("webPort", "" + next.webPort());
        AtomicProperties.write(file, p); options = next;
    }
}

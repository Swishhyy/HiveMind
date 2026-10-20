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

package baritone.utils.mining;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Persistent route endpoints shared by dimensions of one server/save, never by different servers. */
public final class WorldRoutes {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    private final Path file;
    private final Properties properties = new Properties();

    public record Endpoint(String dimension, int x, int y, int z) {
        public Endpoint {
            if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000
                    || y < -2048 || y > 2048) {
                throw new IllegalArgumentException("Invalid route endpoint");
            }
        }
    }

    public WorldRoutes(Path worldRoot) {
        file = worldRoot.resolve("mining-routes.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot read mining routes: " + e.getMessage(), e);
            }
        }
    }

    public Endpoint deposit() { return read("deposit"); }
    public Endpoint portal(String dimension) { return read("portal." + dimension); }

    public void setDeposit(Endpoint endpoint) { write("deposit", endpoint); }
    public void setPortal(Endpoint endpoint) { write("portal." + endpoint.dimension(), endpoint); }
    public void clearDeposit(String dimension) {
        Endpoint current = deposit();
        if (current != null && current.dimension().equals(dimension)) write("deposit", null);
    }
    public void clearPortal(String dimension) { write("portal." + dimension, null); }

    private Endpoint read(String prefix) {
        String dimension = properties.getProperty(prefix + ".dimension");
        if (dimension == null) return null;
        try {
            return new Endpoint(dimension, Integer.parseInt(properties.getProperty(prefix + ".x")),
                    Integer.parseInt(properties.getProperty(prefix + ".y")), Integer.parseInt(properties.getProperty(prefix + ".z")));
        } catch (IllegalArgumentException e) {
            return null; // Never interpret incomplete/malformed coordinates as zero or as another dimension.
        }
    }

    private void write(String prefix, Endpoint endpoint) {
        Properties updated = new Properties();
        updated.putAll(properties);
        for (String part : new String[]{"dimension", "x", "y", "z"}) updated.remove(prefix + "." + part);
        if (endpoint != null) {
            updated.setProperty(prefix + ".dimension", endpoint.dimension());
            updated.setProperty(prefix + ".x", Integer.toString(endpoint.x()));
            updated.setProperty(prefix + ".y", Integer.toString(endpoint.y()));
            updated.setProperty(prefix + ".z", Integer.toString(endpoint.z()));
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "mining-routes-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                updated.store(out, "Baritone mining deposit and Nether portal routes");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            properties.clear();
            properties.putAll(updated);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save mining routes: " + e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    public static boolean connects(String source, String destination) {
        return (OVERWORLD.equals(source) && NETHER.equals(destination))
                || (NETHER.equals(source) && OVERWORLD.equals(destination));
    }

    public static boolean preserveTransition(boolean waiting, boolean connected, long now, long deadline,
                                             String destination, String newDimension) {
        return waiting && connected && destination != null && now < deadline
                && (newDimension == null || destination.equals(newDimension));
    }
}

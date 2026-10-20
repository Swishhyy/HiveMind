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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public record Storage(int id, Endpoint endpoint) {}

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

    public synchronized Endpoint deposit() { return deposits().stream().map(Storage::endpoint).findFirst().orElse(null); }
    public synchronized Endpoint portal(String dimension) { return read("portal." + dimension); }

    /** Legacy single destinations appear as storage #1 until the next storage edit migrates them. */
    public synchronized List<Storage> deposits() {
        if (!properties.containsKey("storage.format")) {
            Endpoint legacy = read("deposit");
            return legacy == null ? List.of() : List.of(new Storage(1, legacy));
        }
        List<Storage> result = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.matches("storage\\.[1-9][0-9]*\\.dimension")) continue;
            try {
                int id = Integer.parseInt(key.substring(8, key.length() - 10));
                Endpoint endpoint = read("storage." + id);
                if (endpoint != null) result.add(new Storage(id, endpoint));
            } catch (IllegalArgumentException ignored) {}
        }
        result.sort(Comparator.comparingInt(Storage::id));
        return List.copyOf(result);
    }

    public synchronized boolean storageConfigured() {
        return properties.containsKey("storage.format") || read("deposit") != null;
    }

    public synchronized void setDeposit(Endpoint endpoint) { addDeposit(endpoint); }

    public synchronized Storage addDeposit(Endpoint endpoint) {
        if (endpoint == null) throw new IllegalArgumentException("A storage endpoint is required");
        for (Storage storage : deposits()) if (storage.endpoint().equals(endpoint)) return storage;
        int max = deposits().stream().mapToInt(Storage::id).max().orElse(0);
        if (max == Integer.MAX_VALUE) throw new IllegalStateException("Storage IDs are exhausted");
        int next = max + 1;
        try { next = Math.max(next, Integer.parseInt(properties.getProperty("storage.nextId", "1"))); }
        catch (NumberFormatException ignored) {}
        if (next <= 0 || next == Integer.MAX_VALUE) throw new IllegalStateException("Storage IDs are exhausted");
        Properties updated = storageUpdate();
        putEndpoint(updated, "storage." + next, endpoint);
        updated.setProperty("storage.nextId", Integer.toString(next + 1));
        save(updated);
        return new Storage(next, endpoint);
    }

    public synchronized boolean removeDeposit(int id) {
        if (deposits().stream().noneMatch(storage -> storage.id() == id)) return false;
        Properties updated = storageUpdate();
        putEndpoint(updated, "storage." + id, null);
        save(updated);
        return true;
    }

    public synchronized void clearDeposits() {
        Properties updated = storageUpdate();
        for (Storage storage : deposits()) putEndpoint(updated, "storage." + storage.id(), null);
        save(updated);
    }

    /** Prefer nearby storage in this dimension before a supported dimension crossing. */
    public synchronized List<Storage> orderedDeposits(String dimension, int x, int y, int z, boolean travel) {
        return deposits().stream()
                .filter(storage -> storage.endpoint().dimension().equals(dimension)
                        || (travel && connects(dimension, storage.endpoint().dimension())))
                .sorted(Comparator.comparingInt((Storage storage) -> storage.endpoint().dimension().equals(dimension) ? 0 : 1)
                        .thenComparingDouble(storage -> storage.endpoint().dimension().equals(dimension)
                                ? distanceSquared(storage.endpoint(), x, y, z) : 0)
                        .thenComparingInt(Storage::id)).toList();
    }

    private static double distanceSquared(Endpoint endpoint, int x, int y, int z) {
        return Math.pow((double) endpoint.x() - x, 2) + Math.pow((double) endpoint.y() - y, 2)
                + Math.pow((double) endpoint.z() - z, 2);
    }

    public synchronized void setPortal(Endpoint endpoint) { write("portal." + endpoint.dimension(), endpoint); }
    public synchronized void clearDeposit(String dimension) {
        Properties updated = storageUpdate();
        for (Storage storage : deposits()) if (storage.endpoint().dimension().equals(dimension)) {
            putEndpoint(updated, "storage." + storage.id(), null);
        }
        save(updated);
    }
    public synchronized void clearPortal(String dimension) { write("portal." + dimension, null); }

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
        putEndpoint(updated, prefix, endpoint);
        save(updated);
    }

    private Properties storageUpdate() {
        Properties updated = new Properties();
        updated.putAll(properties);
        for (Storage storage : deposits()) putEndpoint(updated, "storage." + storage.id(), storage.endpoint());
        putEndpoint(updated, "deposit", null);
        updated.setProperty("storage.format", "1");
        return updated;
    }

    private static void putEndpoint(Properties updated, String prefix, Endpoint endpoint) {
        for (String part : new String[]{"dimension", "x", "y", "z"}) updated.remove(prefix + "." + part);
        if (endpoint != null) {
            updated.setProperty(prefix + ".dimension", endpoint.dimension());
            updated.setProperty(prefix + ".x", Integer.toString(endpoint.x()));
            updated.setProperty(prefix + ".y", Integer.toString(endpoint.y()));
            updated.setProperty(prefix + ".z", Integer.toString(endpoint.z()));
        }
    }

    private void save(Properties updated) {
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

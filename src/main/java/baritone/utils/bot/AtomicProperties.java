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

package baritone.utils.bot;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Commit configuration only after a successful replacement; malformed input is never silently overwritten. */
public final class AtomicProperties {
    private AtomicProperties() {}
    public static Properties read(Path file) {
        Properties result = new Properties();
        if (Files.exists(file)) try (InputStream in = Files.newInputStream(file)) { result.load(in); }
        catch (IOException | IllegalArgumentException e) { throw new IllegalStateException("Cannot read " + file.getFileName(), e); }
        return result;
    }
    public static void write(Path file, Properties values) {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "bot-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { values.store(out, "Baritone bot configuration"); }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save " + file.getFileName(), e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }
}

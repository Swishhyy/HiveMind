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

package hivemind.utils.bot;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Commit configuration only after a successful replacement; malformed input is never silently overwritten. */
public final class AtomicProperties {
    private static final ConcurrentHashMap<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private AtomicProperties() {}
    public static Properties read(Path file) {
        Properties result = new Properties();
        if (Files.exists(file)) try (InputStream in = Files.newInputStream(file)) { result.load(in); }
        catch (IOException | IllegalArgumentException e) {
            for(int i=1;i<=3;i++){
                Path backup=backup(file,i);if(!Files.isRegularFile(backup))continue;
                try(InputStream in=Files.newInputStream(backup)){Properties recovered=new Properties();recovered.load(in);System.err.println("HiveMind recovered "+file.getFileName()+" from snapshot "+i+"; original file retained");return recovered;}
                catch(IOException|IllegalArgumentException ignored){}
            }
            throw new IllegalStateException("Cannot read " + file.getFileName()+"; no readable snapshots", e);
        }
        return result;
    }
    public static void write(Path file, Properties values) {
        file=file.toAbsolutePath().normalize();
        synchronized(LOCKS.computeIfAbsent(file,p->new Object())){writeLocked(file,values);}
    }
    /** Schema validation also participates in recovery; parseable text is not necessarily valid state. */
    public static Properties readValidated(Path file,java.util.function.Consumer<Properties> validator){
        Properties candidate=read(file);
        try{validator.accept(candidate);return candidate;}
        catch(RuntimeException original){
            for(int i=1;i<=3;i++)try(InputStream in=Files.newInputStream(backup(file,i))){
                Properties recovered=new Properties();recovered.load(in);validator.accept(recovered);
                System.err.println("HiveMind recovered validated "+file.getFileName()+" from snapshot "+i+"; original retained");return recovered;
            }catch(IOException|RuntimeException invalid){}
            throw new IllegalStateException("Invalid "+file.getFileName()+"; no valid snapshots",original);
        }
    }
    public static Path backup(Path file,int index){if(index<1||index>3)throw new IllegalArgumentException("Snapshot must be 1–3");return file.resolveSibling(file.getFileName()+".bak"+index);}
    private static void writeLocked(Path file,Properties values){
        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "bot-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { values.store(out, "HiveMind bot configuration"); }
            // Keep only readable prior states. A recovered primary must not replace good snapshots with corrupt bytes.
            if(Files.isRegularFile(file)){
                boolean readable=true;try(InputStream in=Files.newInputStream(file)){new Properties().load(in);}catch(IllegalArgumentException|IOException error){readable=false;}
                if(readable){for(int i=3;i>=2;i--)if(Files.exists(backup(file,i-1)))Files.copy(backup(file,i-1),backup(file,i),StandardCopyOption.REPLACE_EXISTING);Files.copy(file,backup(file,1),StandardCopyOption.REPLACE_EXISTING);}
            }
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot save " + file.getFileName(), e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }
}

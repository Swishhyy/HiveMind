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

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Per-server UUID allowlist. System-message parsing is opt-in and trusts the server's exact format. */
public final class TrustedControl {
    private final Path file;
    private Properties values;
    public TrustedControl(Path root) { file = root.resolve("bot-control.properties"); reload(); }
    public void reload() { values = AtomicProperties.read(file); }
    public Set<UUID> players() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String id : values.getProperty("trusted", "").split(",")) if (!id.isBlank()) ids.add(UUID.fromString(id.strip()));
        return Set.copyOf(ids);
    }
    public boolean trusted(UUID id) { return id != null && players().contains(id); }
    public void change(UUID id, boolean add) {
        apply(Map.of(id,add));
    }
    public void apply(Map<UUID,Boolean> changes) {
        Set<UUID> original=players(),ids=new TreeSet<>(original);
        changes.forEach((id,add)->{if(add)ids.add(id);else ids.remove(id);});
        if(ids.equals(original))return;
        if(ids.size()>128)throw new IllegalArgumentException("A bot may trust at most 128 controllers");
        Properties updated = new Properties(); updated.putAll(values);
        updated.setProperty("trusted", String.join(",", ids.stream().map(UUID::toString).toList()));
        updated.putIfAbsent("whisperTypes", "minecraft:msg_command_incoming");
        updated.putIfAbsent("allowUnsignedServerWhispers", "false");
        updated.putIfAbsent("unsignedWhisperFormat", "");
        AtomicProperties.write(file, updated); values = updated;
    }
    public boolean whisperType(String id) {
        return Arrays.stream(values.getProperty("whisperTypes", "minecraft:msg_command_incoming").split(",")).anyMatch(id::equals);
    }
    public Map.Entry<String, String> parseServerWhisper(String text) {
        String format = values.getProperty("unsignedWhisperFormat", "");
        if (!Boolean.parseBoolean(values.getProperty("allowUnsignedServerWhispers", "false")) || format.isBlank()
                || text == null || text.length() > 512) return null;
        int sender = format.indexOf("{sender}"), command = format.indexOf("{command}");
        if (format.length() > 128 || sender < 0 || command <= sender + 8 || format.lastIndexOf("{sender}") != sender
                || format.lastIndexOf("{command}") != command) throw new IllegalArgumentException("Whisper format needs {sender} before {command}");
        String regex = Pattern.quote(format.substring(0, sender)) + "([A-Za-z0-9_]{1,16})"
                + Pattern.quote(format.substring(sender + 8, command)) + "(.{1,220})" + Pattern.quote(format.substring(command + 9));
        var match = Pattern.compile(regex).matcher(text);
        return match.matches() ? Map.entry(match.group(1), match.group(2)) : null;
    }
    public static String command(String text) {
        if (text == null || text.length() > 220 || text.chars().anyMatch(Character::isISOControl)) return null;
        String command = text.strip();
        if (!command.startsWith("#")) return null;
        command = command.substring(1).strip();
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return Set.of("mine", "job", "status", "pause", "resume", "stop", "cancel", "deposit", "stalk", "kill", "recover", "deliver", "escort", "gather", "repair", "station", "minezone", "roam").contains(root) ? command : null;
    }
}

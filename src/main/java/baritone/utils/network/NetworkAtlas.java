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
import baritone.utils.network.NetworkProtocol.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Bounded, server-scoped history. Replay is idempotent; peer marks never create PlayerMemory base evidence. */
public final class NetworkAtlas {
    private record Chunk(String dimension, int x, int z) {}
    public record Hotspot(UUID player, String name, String dimension, int x, int y, int z,
                          int visits, long dwellSeconds, int samples, List<String> sources, long last) {}
    private final Path file;
    private final Set<Sighting> sightings = new LinkedHashSet<>();
    private final Map<Chunk, Exploration> chunks = new LinkedHashMap<>();
    private final Map<UUID, Exploration> bots = new LinkedHashMap<>();
    private long revision, savedRevision, lastPrune;
    public synchronized boolean visited(String dimension,int x,int z){return chunks.containsKey(new Chunk(dimension,x,z));}
    public NetworkAtlas() { file = null; }
    public NetworkAtlas(Path root) {
        file = root.resolve("bot-network-atlas.properties");
        try {
            if (Files.exists(file) && Files.size(file) > 8_000_000) throw new IOException("Atlas file exceeds its size limit");
            Properties p = AtomicProperties.read(file); int count = Integer.parseInt(p.getProperty("count", "0"));
            if (count < 0 || count > 12544) throw new IOException("Atlas record limit exceeded");
            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                String encoded = p.getProperty("entry." + i, "");
                if (encoded.length() > 1400) throw new IOException("Oversized atlas entry");
                Message message = NetworkProtocol.decode(Base64.getDecoder().decode(encoded));
                if (message != null) accept(message, now);
            }
            savedRevision = revision;
        } catch (IOException | IllegalArgumentException e) { throw new IllegalStateException("Invalid bot-network-atlas.properties; correct it before recording more history", e); }
    }
    public synchronized boolean accept(Message message, long now) {
        prune(now);
        if (message == null || !(message instanceof Sighting||message instanceof Exploration) || !message.valid(now)) return false;
        if (message instanceof Sighting sighting) {
            if (!sightings.add(sighting)) return false;
            if (sightings.size() > 4096) sightings.remove(sightings.stream().min(Comparator.comparingLong(Sighting::observedAt)).orElseThrow());
        } else {
            Exploration exploration = (Exploration) message;
            Chunk chunk = new Chunk(exploration.point().dimension(), Math.floorDiv(exploration.point().x(), 16), Math.floorDiv(exploration.point().z(), 16));
            Exploration last = chunks.get(chunk), bot = bots.get(exploration.reporter());
            boolean moved = bot == null || bot.observedAt() < exploration.observedAt();
            if (moved) bots.put(exploration.reporter(), exploration);
            while (bots.size() > 256) bots.remove(bots.keySet().iterator().next());
            if (last != null && last.observedAt() >= exploration.observedAt()) { if (moved) revision++; return moved; }
            chunks.put(chunk, exploration);
            if (chunks.size() > 8192) chunks.remove(chunks.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().observedAt())).orElseThrow().getKey());
        }
        revision++; return true;
    }
    public synchronized boolean dirty() { return revision != savedRevision; }
    public synchronized List<Message> snapshot(long now) {
        prune(now); Set<Message> distinct = new LinkedHashSet<>(sightings); distinct.addAll(chunks.values()); distinct.addAll(bots.values()); List<Message> result = new ArrayList<>(distinct);
        result.sort(Comparator.comparingLong(Message::observedAt).reversed()); return result;
    }
    public synchronized void clear() { sightings.clear(); chunks.clear(); bots.clear(); revision++; }
    public void save() {
        if (file == null) return;
        List<Message> snapshot; long version;
        synchronized (this) { if (!dirty()) return; snapshot = snapshot(System.currentTimeMillis()); version = revision; }
        Properties p = new Properties(); p.setProperty("count", "" + snapshot.size());
        try {
            for (int i = 0; i < snapshot.size(); i++) p.setProperty("entry." + i, Base64.getEncoder().encodeToString(NetworkProtocol.encode(snapshot.get(i))));
        } catch (IOException e) { throw new IllegalStateException("Cannot encode network atlas", e); }
        AtomicProperties.write(file, p);
        synchronized (this) { savedRevision = version; }
    }
    private void prune(long now) {
        if (now - lastPrune < 10000) return;
        lastPrune = now; int before = sightings.size() + chunks.size();
        sightings.removeIf(s -> !s.valid(now)); chunks.values().removeIf(s -> !s.valid(now)); bots.values().removeIf(s -> !s.valid(now));
        if (before != sightings.size() + chunks.size()) revision++;
    }
    public synchronized List<Hotspot> hotspots(long now) {
        prune(now);
        record Area(UUID player, String dimension, int x, int z) {}
        Map<Area, List<Sighting>> groups = new LinkedHashMap<>();
        for (Sighting sighting : sightings) {
            var p = sighting.point();
            groups.computeIfAbsent(new Area(sighting.player(), p.dimension(), Math.floorDiv(p.x(), 64), Math.floorDiv(p.z(), 64)), unused -> new ArrayList<>()).add(sighting);
        }
        List<Hotspot> result = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Sighting> seen = entry.getValue(); seen.sort(Comparator.comparingLong(Sighting::observedAt));
            if (seen.size() < 3 || seen.get(seen.size() - 1).observedAt() - seen.get(0).observedAt() < 30000) continue;
            int visits = 1; long dwell = 0, previous = seen.get(0).observedAt();
            for (int i = 1; i < seen.size(); i++) {
                long time = seen.get(i).observedAt(), gap = time - previous;
                if (gap >= 120000) visits++; else if (gap <= 30000) dwell += gap;
                previous = time;
            }
            Sighting latest = seen.get(seen.size() - 1); Area area = entry.getKey();
            result.add(new Hotspot(area.player(), latest.playerName(), area.dimension(), area.x() * 64 + 32, latest.point().y(), area.z() * 64 + 32,
                    visits, dwell / 1000, seen.size(), seen.stream().map(Sighting::reporterName).distinct().sorted().toList(), latest.observedAt()));
        }
        return result.stream().sorted(Comparator.comparingLong((Hotspot h) -> h.visits() * 120L + h.dwellSeconds()).reversed()).limit(128).toList();
    }
    /** JSON is constructed explicitly so obfuscation never changes the wire format and no reflective serialization is needed. */
    public synchronized String json(long now, String status, String dimension, List<Identity> peers) {
        prune(now); StringBuilder out = new StringBuilder(4096);
        out.append("{\"now\":").append(now).append(",\"status\":").append(quote(status)).append(",\"dimension\":").append(quote(dimension))
                .append(",\"samples\":").append(sightings.size()).append(",\"peers\":[");
        StringJoiner values = new StringJoiner(",");
        for (Identity peer : peers) values.add("{\"id\":" + quote(peer.id().toString()) + ",\"name\":" + quote(peer.name()) + "}");
        out.append(values).append("],\"chunks\":["); values = new StringJoiner(",");
        for (var entry : chunks.entrySet()) {
            Chunk c = entry.getKey(); Exploration e = entry.getValue();
            values.add("{\"d\":" + quote(c.dimension()) + ",\"x\":" + c.x() + ",\"z\":" + c.z() + ",\"name\":" + quote(e.reporterName()) + ",\"t\":" + e.observedAt() + "}");
        }
        out.append(values).append("],\"bots\":["); values = new StringJoiner(",");
        for (Exploration bot : bots.values()) values.add("{\"id\":" + quote(bot.reporter().toString()) + ",\"name\":" + quote(bot.reporterName()) + "," + point(bot.point()) + ",\"t\":" + bot.observedAt() + "}");
        out.append(values).append("],\"sightings\":["); values = new StringJoiner(",");
        Map<String, Sighting> latest = new LinkedHashMap<>();
        for (Sighting sighting : sightings.stream().sorted(Comparator.comparingLong(Sighting::observedAt)).toList())
            latest.put(sighting.player() + ":" + sighting.point().dimension(), sighting);
        for (Sighting sighting : latest.values().stream().sorted(Comparator.comparingLong(Sighting::observedAt).reversed()).limit(512).toList())
            values.add("{\"id\":" + quote(sighting.player().toString()) + ",\"name\":" + quote(sighting.playerName()) + ",\"source\":" + quote(sighting.reporterName()) + "," + point(sighting.point()) + ",\"t\":" + sighting.observedAt() + "}");
        out.append(values).append("],\"hotspots\":["); values = new StringJoiner(",");
        for (Hotspot h : hotspots(now))
            values.add("{\"id\":" + quote(h.player().toString()) + ",\"name\":" + quote(h.name()) + ",\"d\":" + quote(h.dimension()) + ",\"x\":" + h.x() + ",\"y\":" + h.y() + ",\"z\":" + h.z()
                    + ",\"visits\":" + h.visits() + ",\"dwell\":" + h.dwellSeconds() + ",\"samples\":" + h.samples() + ",\"sources\":[" + h.sources().stream().map(NetworkAtlas::quote).collect(Collectors.joining(",")) + "],\"t\":" + h.last() + "}");
        return out.append(values).append("]}").toString();
    }
    private static String point(baritone.utils.mining.WorldRoutes.Endpoint p) { return "\"d\":" + quote(p.dimension()) + ",\"x\":" + p.x() + ",\"y\":" + p.y() + ",\"z\":" + p.z(); }
    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 32 || ch == '<' || ch == '>' || ch == '&') out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.append('"').toString();
    }
}

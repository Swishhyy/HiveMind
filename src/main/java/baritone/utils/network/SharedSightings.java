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

import baritone.utils.network.NetworkProtocol.Sighting;
import java.util.*;

/** Ephemeral peer marks, separate from firsthand observations and base/visit evidence. One store per server scope. */
public final class SharedSightings {
    private record Key(UUID player, String dimension) {}
    private final Map<Key, Sighting> marks = new LinkedHashMap<>();
    public synchronized boolean accept(Sighting report, long now, long ttl) {
        prune(now, ttl);
        if (!report.fresh(now, ttl)) return false;
        Key key = new Key(report.player(), report.point().dimension()); Sighting previous = marks.get(key);
        if (previous != null && previous.observedAt() >= report.observedAt()) return false;
        marks.remove(key); marks.put(key, report);
        while (marks.size() > 512) marks.remove(marks.keySet().iterator().next());
        return true;
    }
    public synchronized List<Sighting> all(long now, long ttl) {
        prune(now, ttl); return marks.values().stream().sorted(Comparator.comparingLong(Sighting::observedAt).reversed()).toList();
    }
    public synchronized Sighting latest(UUID player, String dimension, long now, long ttl) {
        prune(now, ttl); return marks.get(new Key(player, dimension));
    }
    public synchronized UUID resolve(String name, long now, long ttl) {
        return all(now, ttl).stream().filter(s -> s.playerName().equalsIgnoreCase(name)).map(Sighting::player).findFirst().orElse(null);
    }
    public synchronized void clear() { marks.clear(); }
    /** A fresh peer report may change an active search, but repeated stationary reports must not restart its route/wait. */
    public static boolean shouldRedirect(Sighting candidate, Sighting previous, long localSeenAt, long lastRedirectAt, long now) {
        if (candidate == null || !candidate.fresh(now, NetworkProtocol.MAX_AGE_MS) || candidate.observedAt() <= localSeenAt
                || lastRedirectAt != 0 && now - lastRedirectAt < 10000) return false;
        if (previous == null) return true;
        if (!candidate.point().dimension().equals(previous.point().dimension())) return true;
        double dx = (double) candidate.point().x() - previous.point().x(), dy = (double) candidate.point().y() - previous.point().y(), dz = (double) candidate.point().z() - previous.point().z();
        return dx * dx + dy * dy + dz * dz >= 256;
    }
    private void prune(long now, long ttl) { marks.values().removeIf(s -> !s.fresh(now, ttl)); }
}

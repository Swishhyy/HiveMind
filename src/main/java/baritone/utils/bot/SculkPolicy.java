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

import java.util.*;

/** Immutable spatial index: path threads never read mutable live hazard lists. */
public final class SculkPolicy {
    private SculkPolicy() {}
    public record Zone(int x, int y, int z, int radius) {
        public Zone { radius = SurvivalPolicy.bounded(radius, 4, 16); }
        public double distanceSquared(int px, int py, int pz) {
            return Math.pow((double) px - x, 2) + Math.pow((double) py - y, 2) + Math.pow((double) pz - z, 2);
        }
    }
    public static boolean sculk(String path) { return path.startsWith("sculk") || path.equals("calibrated_sculk_sensor"); }
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(List.of());
        private final Map<Long, List<Zone>> cells;
        private final List<Zone> zones;
        public Snapshot(List<Zone> input) {
            zones = List.copyOf(input);
            Map<Long, List<Zone>> index = new HashMap<>();
            for (Zone zone : zones) index.computeIfAbsent(key(zone.x() >> 5, zone.z() >> 5), k -> new ArrayList<>()).add(zone);
            Map<Long, List<Zone>> frozen = new HashMap<>(); index.forEach((k, v) -> frozen.put(k, List.copyOf(v))); cells = Map.copyOf(frozen);
        }
        public List<Zone> zones() { return zones; }
        public boolean contains(int x, int y, int z) { return penalty(x, y, z) > 0; }
        public double penalty(int x, int y, int z) {
            double result = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                for (Zone zone : cells.getOrDefault(key((x >> 5) + dx, (z >> 5) + dz), List.of())) {
                    double d = zone.distanceSquared(x, y, z);
                    if (d <= zone.radius() * zone.radius()) result = Math.max(result, 1 + zone.radius() - Math.sqrt(d));
                }
            }
            return result;
        }
        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    }
}

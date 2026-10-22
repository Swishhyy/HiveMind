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

import java.util.*;

/** Inclusive coordinates and overflow-safe containment; zones constrain digging, not ordinary walking. */
public record MiningZone(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public MiningZone {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || minX > maxX || minY > maxY || minZ > maxZ || minY < -2048 || maxY > 2048
                || Math.abs((long) minX) > 30000000 || Math.abs((long) maxX) > 30000000
                || Math.abs((long) minZ) > 30000000 || Math.abs((long) maxZ) > 30000000) throw new IllegalArgumentException("Invalid mining boundary");
    }
    public static MiningZone between(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new MiningZone(dimension, Math.min(x1,x2), Math.min(y1,y2), Math.min(z1,z2), Math.max(x1,x2), Math.max(y1,y2), Math.max(z1,z2));
    }
    public boolean contains(String world, int x, int y, int z) {
        return dimension.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public List<int[]> exploration(int x, int y, int z) {
        List<int[]> points = new ArrayList<>(); Set<String> seen = new HashSet<>();
        int anchorX = Math.max(minX, Math.min(maxX,x)), anchorZ = Math.max(minZ,Math.min(maxZ,z));
        int atY = Math.max(minY,Math.min(maxY,y));
        for (int ring = 0; ring <= 64 && points.size() < 4096; ring++) {
            for (int dx = -ring; dx <= ring && points.size() < 4096; dx++) for (int dz = -ring; dz <= ring && points.size() < 4096; dz++) {
                if (ring != 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                int px = Math.max(minX,Math.min(maxX,anchorX + dx*16));
                int pz = Math.max(minZ,Math.min(maxZ,anchorZ + dz*16));
                if (seen.add(px+":"+pz)) points.add(new int[]{px,atY,pz});
            }
        }
        return List.copyOf(points);
    }
}

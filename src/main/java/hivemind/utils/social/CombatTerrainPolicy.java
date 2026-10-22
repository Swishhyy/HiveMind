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

package hivemind.utils.social;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded, terrain-independent support planning for legal block-face placement. */
public final class CombatTerrainPolicy {
    private CombatTerrainPolicy() {}
    private record Node<T>(T cell,List<T> path) {}
    /** Empty means already clickable; null means no legal support chain. Supports are returned in build order. */
    public static <T> List<T> supports(T destination,int maximum,Function<T,List<T>> neighbors,Predicate<T> buildable,Predicate<T> clickable) {
        int limit=StealthPolicy.bounded(maximum,0,2);
        ArrayDeque<Node<T>> queue=new ArrayDeque<>();Set<T> seen=new HashSet<>();
        queue.add(new Node<>(destination,List.of(destination)));seen.add(destination);
        while(!queue.isEmpty()) {
            Node<T> node=queue.remove();
            if(clickable.test(node.cell())) {
                List<T> result=new ArrayList<>(node.path().subList(1,node.path().size()));Collections.reverse(result);return List.copyOf(result);
            }
            if(node.path().size()>limit)continue;
            for(T next:neighbors.apply(node.cell()))if(seen.add(next)&&buildable.test(next)) {
                List<T> path=new ArrayList<>(node.path());path.add(next);queue.add(new Node<>(next,List.copyOf(path)));
            }
        }
        return null;
    }
    public static boolean lureRange(double travelSquared,double opponentSquared) {
        return Double.isFinite(travelSquared)&&Double.isFinite(opponentSquared)&&travelSquared>=4&&travelSquared<=64
                &&opponentSquared>=9&&opponentSquared<=196;
    }
    public static boolean crystalTopReachable(int baseY,double eyeY) {
        return Double.isFinite(eyeY)&&baseY+1.0<eyeY+1.25;
    }
}

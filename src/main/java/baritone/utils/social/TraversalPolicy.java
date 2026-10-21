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

package baritone.utils.social;

import baritone.utils.mining.ConstructionPolicy;
import java.util.Set;

/** Bucket falls require a real clear landing; quiet tracking adds costs without banning necessary building. */
public final class TraversalPolicy {
    private TraversalPolicy(){}
    public static double evidencePenalty(boolean tracking,boolean hunting,boolean minimize,int configured){return tracking&&!hunting&&minimize?Math.max(0,Math.min(10000,configured)):0;}
    public static double armHeight(double reach,double verticalSpeed){return Math.max(12,Math.min(32,reach+Math.abs(verticalSpeed)*3+2));}
    public static boolean bucketLanding(boolean waterWorks,boolean emptySpace,boolean fullTop,String block){
        return waterWorks&&emptySpace&&fullTop&&!ConstructionPolicy.isContainer(block)&&!Set.of("magma_block","campfire","soul_campfire","cactus","pointed_dripstone","powder_snow","furnace","blast_furnace","smoker","crafting_table","enchanting_table","ender_chest").contains(block);
    }
    public static int bucketHeight(int configured){return Math.max(3,Math.min(1024,configured));}
}

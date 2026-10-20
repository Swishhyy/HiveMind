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

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Only start mining if Baritone's loot model includes the requested item. Unavailable sources require storage. */
public final class GatherSources {
    private GatherSources(){}
    public static List<String> blocks(String item){
        var explicit=Map.ofEntries(Map.entry("minecraft:diamond",List.of("diamond_ore","deepslate_diamond_ore")),Map.entry("minecraft:coal",List.of("coal_ore","deepslate_coal_ore")),
                Map.entry("minecraft:raw_iron",List.of("iron_ore","deepslate_iron_ore")),Map.entry("minecraft:raw_gold",List.of("gold_ore","deepslate_gold_ore")),
                Map.entry("minecraft:raw_copper",List.of("copper_ore","deepslate_copper_ore")),Map.entry("minecraft:redstone",List.of("redstone_ore","deepslate_redstone_ore")),
                Map.entry("minecraft:lapis_lazuli",List.of("lapis_ore","deepslate_lapis_ore")),Map.entry("minecraft:emerald",List.of("emerald_ore","deepslate_emerald_ore")),
                Map.entry("minecraft:cobblestone",List.of("stone","cobblestone")),Map.entry("minecraft:cobbled_deepslate",List.of("deepslate","cobbled_deepslate")),Map.entry("minecraft:clay_ball",List.of("clay")),Map.entry("minecraft:quartz",List.of("nether_quartz_ore")));
        if(explicit.containsKey(item))return explicit.get(item).stream().map(id->"minecraft:"+id).toList();
        var value=BuiltInRegistries.ITEM.getValue(Identifier.parse(item));
        if(value instanceof BlockItem block&&new BlockOptionalMeta(block.getBlock()).matches(new ItemStack(value)))return List.of(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
        return List.of();
    }
    public static String dimension(String item,String current){if(item.equals("minecraft:ancient_debris")||item.equals("minecraft:quartz")||item.equals("minecraft:netherrack"))return "minecraft:the_nether";return "minecraft:overworld";}
}

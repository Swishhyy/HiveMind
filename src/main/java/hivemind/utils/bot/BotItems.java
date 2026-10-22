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

import hivemind.api.utils.IPlayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public final class BotItems {
    private BotItems(){}
    public static String id(ItemStack stack){return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}
    public static String require(String input){Identifier key=Identifier.parse(input.contains(":")?input:"minecraft:"+input);if(!BuiltInRegistries.ITEM.containsKey(key)||key.toString().equals("minecraft:air"))throw new IllegalArgumentException("Unknown item "+key);return key.toString();}
    public static boolean ordinary(ItemStack stack){return !stack.isEmpty()&&stack.getComponentsPatch().isEmpty();}
    public static int count(IPlayerContext ctx,String id){return BotInventory.count(ctx,stack->id(stack).equals(id)&&ordinary(stack));}
    public static int slot(IPlayerContext ctx,String id){return BotInventory.slot(ctx,stack->id(stack).equals(id)&&ordinary(stack));}
    public static Map<String,Integer> inventory(IPlayerContext ctx){Map<String,Integer> map=new HashMap<>();for(int i=0;i<36;i++){ItemStack stack=ctx.player().getInventory().getItem(i);if(ordinary(stack))map.merge(id(stack),stack.getCount(),Integer::sum);}return map;}
    public static int menuSlot(int inventorySlot,int totalSlots){int first=totalSlots-36;return first+(inventorySlot<9?27+inventorySlot:inventorySlot-9);}
}

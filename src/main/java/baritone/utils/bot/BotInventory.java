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

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import java.util.function.Predicate;

public final class BotInventory {
    private BotInventory() {}
    public static boolean food(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD) && Baritone.settings().botFoodItems.value.contains(stack.getItem());
    }
    public static boolean building(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && !(block.getBlock() instanceof FallingBlock)
                && Baritone.settings().acceptableThrowawayItems.value.contains(stack.getItem());
    }
    public static boolean pickaxe(ItemStack stack, boolean debris) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return !stack.isEmpty() && id.endsWith("_pickaxe") && stack.isDamageableItem()
                && SurvivalPolicy.usableTool(stack.getMaxDamage() - stack.getDamageValue(), Baritone.settings().botToolReserve.value)
                && (!debris || id.equals("minecraft:diamond_pickaxe") || id.equals("minecraft:netherite_pickaxe"));
    }
    public static int count(IPlayerContext ctx, Predicate<ItemStack> filter) {
        int total = 0;
        for (int i = 0; i < 36; i++) { ItemStack stack = ctx.player().getInventory().getItem(i); if (filter.test(stack)) total += stack.getCount(); }
        return total;
    }
    public static int slot(IPlayerContext ctx, Predicate<ItemStack> filter) {
        for (int i = 0; i < 36; i++) if (filter.test(ctx.player().getInventory().getItem(i))) return i;
        return -1;
    }
    public static boolean needsFood(IPlayerContext ctx) { return count(ctx, BotInventory::food) < SurvivalPolicy.bounded(Baritone.settings().botMinimumFood.value, 1, 64); }
    public static boolean needsBlocks(IPlayerContext ctx) { return Baritone.settings().allowPlace.value && count(ctx, BotInventory::building) < SurvivalPolicy.bounded(Baritone.settings().botMinimumBlocks.value, 0, 128); }
}

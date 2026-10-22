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

import hivemind.HiveMind;
import hivemind.api.utils.IPlayerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import java.util.function.Predicate;
import hivemind.api.utils.BlockOptionalMetaLookup;
import hivemind.utils.mining.WastePolicy;
import java.util.ArrayList;
import java.util.Set;

public final class BotInventory {
    private BotInventory() {}
    public static boolean food(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD) && HiveMind.settings().botFoodItems.value.contains(stack.getItem());
    }
    public static boolean building(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && !(block.getBlock() instanceof FallingBlock)
                && HiveMind.settings().acceptableThrowawayItems.value.contains(stack.getItem());
    }
    public static boolean pickaxe(ItemStack stack, boolean debris) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return !stack.isEmpty() && id.endsWith("_pickaxe") && stack.isDamageableItem()
                && SurvivalPolicy.usableTool(stack.getMaxDamage() - stack.getDamageValue(), HiveMind.settings().botToolReserve.value)
                && (!debris || id.equals("minecraft:diamond_pickaxe") || id.equals("minecraft:netherite_pickaxe"));
    }
    public static Set<Integer> buildingReserves(IPlayerContext ctx, BlockOptionalMetaLookup targets) {
        var stacks = new ArrayList<WastePolicy.Stack>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = ctx.player().getInventory().getItem(slot);
            if (building(stack)) stacks.add(new WastePolicy.Stack(slot, stack.getCount(), true, targets != null && targets.has(stack)));
        }
        return WastePolicy.buildingSlots(stacks, Math.max(1, HiveMind.settings().mineWasteKeepStacks.value));
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
    public static boolean needsFood(IPlayerContext ctx) { return count(ctx, BotInventory::food) < SurvivalPolicy.bounded(HiveMind.settings().botMinimumFood.value, 1, 64); }
    public static boolean needsBlocks(IPlayerContext ctx) { return HiveMind.settings().allowPlace.value && count(ctx, BotInventory::building) < SurvivalPolicy.bounded(HiveMind.settings().botMinimumBlocks.value, 0, 128); }
}

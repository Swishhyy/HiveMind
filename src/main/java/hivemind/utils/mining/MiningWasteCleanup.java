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

package hivemind.utils.mining;

import hivemind.HiveMind;
import hivemind.api.HiveMindAPI;
import hivemind.utils.bot.BotItems;
import hivemind.api.utils.BlockOptionalMetaLookup;
import hivemind.api.utils.IPlayerContext;
import hivemind.api.utils.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Runs only while the mining process owns control, before deciding whether a chest trip is necessary. */
public final class MiningWasteCleanup {
    private final HiveMind hivemind;
    private final IPlayerContext ctx;
    private final Set<Integer> attempted = new HashSet<>();
    private boolean active;
    private int ticks;
    private int waitTicks;
    private int cooldown;
    private Rotation throwRotation;

    public MiningWasteCleanup(HiveMind hivemind) {
        this.hivemind = hivemind;
        this.ctx = hivemind.getPlayerContext();
    }

    public boolean isActive() {
        return active;
    }

    public void reset() {
        active = false;
        ticks = waitTicks = cooldown = 0;
        throwRotation = null;
        attempted.clear();
    }

    /** True means the mining process must pause this tick. Never takes items onto the cursor. */
    public boolean tick(boolean safeToCancel, BlockOptionalMetaLookup targets) {
        if (!HiveMind.settings().mineDiscardWaste.value
                || targets == null || ctx.player() == null || ctx.world() == null
                || ctx.minecraft().gui.screen() != null || ctx.player().containerMenu != ctx.player().inventoryMenu
                || !ctx.player().inventoryMenu.getCarried().isEmpty()) {
            reset();
            return false;
        }
        if (!active) {
            if (cooldown > 0) {
                cooldown--;
                return false;
            }
            long empty = ctx.player().getInventory().getNonEquipmentItems().stream().limit(36).filter(ItemStack::isEmpty).count();
            if (empty > Math.max(0, Math.min(35, HiveMind.settings().mineDepositFreeSlots.value))) return false;
            if (WastePolicy.nextDiscard(snapshot(ctx, targets), keepStacks(), Set.of()).isEmpty()) return false;
            throwRotation = findThrowDirection();
            if (throwRotation == null) {
                cooldown = 100;
                return false;
            }
            active = true;
            ticks = 0;
            attempted.clear();
        }
        if (++ticks > 200) return finish();
        if (!safeToCancel || !ctx.player().onGround()
                || ctx.player().getDeltaMovement().multiply(1, 0, 1).lengthSqr() > 0.0001) return true;

        hivemind.getInputOverrideHandler().clearAllKeys();
        ctx.playerController().resetBlockRemoving();
        hivemind.getLookBehavior().updateTarget(throwRotation, true);
        if (waitTicks > 0) {
            waitTicks--;
            return true;
        }
        Rotation facing = hivemind.getLookBehavior().getEffectiveRotation().orElseGet(ctx::playerRotations);
        if (Math.abs(Math.IEEEremainder(facing.getYaw() - throwRotation.getYaw(), 360)) > 5
                || Math.abs(facing.getPitch() - throwRotation.getPitch()) > 5) return true;

        // Recompute from the live inventory before every single click. Do not queue drop packets.
        OptionalInt next = WastePolicy.nextDiscard(snapshot(ctx, targets), keepStacks(), attempted);
        if (next.isEmpty()) return finish();
        int slot = next.getAsInt();
        attempted.add(slot);
        ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId, WastePolicy.menuSlot(slot),
                1, ContainerInput.THROW, ctx.player());
        waitTicks = Math.max(5, Math.min(20, HiveMind.settings().ticksBetweenInventoryMoves.value));
        return true;
    }

    private boolean finish() {
        active = false;
        ticks = waitTicks = 0;
        throwRotation = null;
        attempted.clear();
        cooldown = 100;
        return false;
    }

    /** Prefer the cleared tunnel behind the bot, then either side; avoid throwing into its forward route. */
    private Rotation findThrowDirection() {
        float yaw = ctx.playerRotations().getYaw();
        for (float offset : new float[]{180, 90, -90}) {
            Direction direction = Direction.fromYRot(yaw + offset);
            boolean clear = true;
            for (int distance = 1; distance <= 3; distance++) {
                BlockPos pos = ctx.playerFeet().relative(direction, distance).above();
                if (!ctx.world().hasChunkAt(pos) || !ctx.world().getBlockState(pos).isAir()) {
                    clear = false;
                    break;
                }
            }
            if (clear) return new Rotation(direction.toYRot(), 0);
        }
        return null;
    }

    public static Set<Integer> reservedBuildingSlots(IPlayerContext ctx, BlockOptionalMetaLookup targets) {
        return HiveMind.settings().mineDiscardWaste.value
                ? WastePolicy.buildingSlots(snapshot(ctx, targets), keepStacks()) : Set.of();
    }

    private static int keepStacks() {
        return HiveMind.settings().mineWasteKeepStacks.value;
    }

    private static List<WastePolicy.Stack> snapshot(IPlayerContext ctx, BlockOptionalMetaLookup targets) {
        List<WastePolicy.Stack> stacks = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = ctx.player().getInventory().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem block)
                    || !HiveMind.settings().mineWasteItems.value.contains(stack.getItem())) continue;
            boolean protectedItem = (targets != null && targets.has(stack))
                    || (HiveMindAPI.getProvider().getHiveMindForPlayer(ctx.player()) instanceof HiveMind bot
                        && bot.getCompanionProcess().reserves(BotItems.id(stack)))
                    || HiveMind.settings().depositKeepItems.value.contains(stack.getItem())
                    || stack.isDamageableItem() || stack.has(DataComponents.FOOD) || stack.has(DataComponents.EQUIPPABLE)
                    || !stack.getComponentsPatch().isEmpty();
            stacks.add(new WastePolicy.Stack(slot, stack.getCount(), !(block.getBlock() instanceof FallingBlock), protectedItem));
        }
        return stacks;
    }
}

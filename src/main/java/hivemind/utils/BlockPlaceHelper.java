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

package hivemind.utils;

import hivemind.HiveMind;
import hivemind.api.utils.IPlayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class BlockPlaceHelper {
    // base ticks between places caused by tick logic
    private static final int BASE_PLACE_DELAY = 1;

    private final IPlayerContext ctx;
    private int rightClickTimer;

    BlockPlaceHelper(IPlayerContext playerContext) {
        this.ctx = playerContext;
    }

    public void tick(boolean rightClickRequested) {
        if (hivemind.api.HiveMindAPI.getProvider().getHiveMindForPlayer(ctx.player()) instanceof HiveMind bot && bot.getRoamProcess().terrainLocked()&&!ctx.player().getMainHandItem().is(net.minecraft.world.item.Items.WATER_BUCKET)&&!ctx.player().getMainHandItem().is(net.minecraft.world.item.Items.BUCKET)) return;
        if (rightClickTimer > 0 && !(ctx.player().getMainHandItem().is(net.minecraft.world.item.Items.WATER_BUCKET)&&!ctx.player().onGround()&&ctx.player().getDeltaMovement().y<-.2)) {
            rightClickTimer--;
            return;
        }
        HitResult mouseOver = ctx.objectMouseOver();
        if (!rightClickRequested || ctx.player().isHandsBusy() || mouseOver == null || mouseOver.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (HiveMind.settings().mineAvoidSculk.value && hivemind.api.HiveMindAPI.getProvider().getHiveMindForPlayer(ctx.player()) instanceof HiveMind bot) {
            var target = ((BlockHitResult) mouseOver).getBlockPos();
            var placed = target.relative(((BlockHitResult) mouseOver).getDirection());
            if (bot.getSculkProcess().snapshot().contains(target.getX(), target.getY(), target.getZ())
                    || bot.getSculkProcess().snapshot().contains(placed.getX(), placed.getY(), placed.getZ())) return;
        }
        rightClickTimer = HiveMind.settings().rightClickSpeed.value - BASE_PLACE_DELAY;
        for (InteractionHand hand : InteractionHand.values()) {
            if (ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), hand, (BlockHitResult) mouseOver) == InteractionResult.SUCCESS) {
                ctx.player().swing(hand, SwingAnimation.DEFAULT, false);
                return;
            }
            if (!ctx.player().getItemInHand(hand).isEmpty() && ctx.playerController().processRightClick(ctx.player(), ctx.world(), hand) == InteractionResult.SUCCESS) {
                return;
            }
        }
    }
}

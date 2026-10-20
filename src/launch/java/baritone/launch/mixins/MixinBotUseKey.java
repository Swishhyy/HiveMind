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

package baritone.launch.mixins;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep vanilla item-use alive while a controlling care/defense process owns the action. */
@Mixin(KeyMapping.class)
public abstract class MixinBotUseKey {
    @Inject(method = "isDown", at = @At("RETURN"), cancellable = true)
    private void botUseKey(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if ((Object) this != mc.options.keyUse || mc.player == null) return;
        if (BaritoneAPI.getProvider().getBaritoneForPlayer(mc.player) instanceof Baritone bot
                && (bot.getSurvivalProcess().holdsUse() || bot.getConflictProcess().holdsUse())) cir.setReturnValue(true);
    }
}

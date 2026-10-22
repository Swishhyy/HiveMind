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

package hivemind.launch.mixins;

import hivemind.HiveMind;
import hivemind.api.HiveMindAPI;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep vanilla item-use alive while a controlling care/defense/stalk process owns the action. */
@Mixin(KeyMapping.class)
public abstract class MixinBotUseKey {
    @Inject(method = "isDown", at = @At("RETURN"), cancellable = true)
    private void botUseKey(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if ((Object) this != mc.options.keyUse || mc.player == null) return;
        if (HiveMindAPI.getProvider().getHiveMindForPlayer(mc.player) instanceof HiveMind bot
                && (bot.getSurvivalProcess().holdsUse() || bot.getConflictProcess().holdsUse() || bot.getStalkProcess().holdsUse())) cir.setReturnValue(true);
    }
}

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
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.time.Instant;

@Mixin(ChatListener.class)
public abstract class MixinBotChatListener {
    @Inject(method = "showMessageToPlayer", at = @At("RETURN"))
    private void trustedWhisper(ChatType.Bound bound, PlayerChatMessage message, Component decorated, GameProfile sender,
                                boolean secureOnly, Instant received, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        if (HiveMindAPI.getProvider().getHiveMindForPlayer(Minecraft.getInstance().player) instanceof HiveMind bot) {
            try { bot.getBotControl().onSignedWhisper(message, sender, bound); }
            catch (RuntimeException e) { bot.getBotControl().reportError(e); }
        }
    }
    @Inject(method = "handleSystemMessage", at = @At("RETURN"))
    private void optionalServerWhisper(Component message, boolean remote, CallbackInfo ci) {
        if (HiveMindAPI.getProvider().getHiveMindForPlayer(Minecraft.getInstance().player) instanceof HiveMind bot) {
            try { bot.getBotControl().onServerMessage(message.getString()); }
            catch (RuntimeException e) { bot.getBotControl().reportError(e); }
        }
    }
}

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

import baritone.utils.ChatControlHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {

    @Shadow
    public abstract String normalizeChatMessage(String message);

    @Inject(
            method = "handleChatInput(Ljava/lang/String;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void handleBaritoneChatInput(String message, boolean addToRecent, CallbackInfo ci) {
        String normalized = this.normalizeChatMessage(message);
        if (!ChatControlHelper.isPrefixedBaritoneCommand(normalized)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (ChatControlHelper.dispatchChatControl(minecraft, normalized)) {
            if (addToRecent) {
                minecraft.gui.getChat().addRecentChat(normalized);
            }
            ci.cancel();
        }
    }
}

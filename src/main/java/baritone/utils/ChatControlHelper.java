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

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.ChatEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

public final class ChatControlHelper {

    private ChatControlHelper() {}

    public static boolean isPrefixedBaritoneCommand(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        if (message.startsWith(FORCE_COMMAND_PREFIX)) {
            return true;
        }

        Settings settings = BaritoneAPI.getSettings();
        String prefix = settings.prefix.value;
        return settings.prefixControl.value && !prefix.isEmpty() && message.startsWith(prefix);
    }

    public static boolean dispatchChatControl(Minecraft minecraft, String message) {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        return dispatchChatControl(player, message);
    }

    public static boolean dispatchChatControl(LocalPlayer player, String message) {
        if (message == null) {
            return false;
        }

        IBaritone baritone = player == null ? null : BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        if (baritone == null) {
            baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        }

        if (baritone == null) {
            return false;
        }
        ChatEvent event = new ChatEvent(message);
        baritone.getGameEventHandler().onSendChatMessage(event);
        return event.isCancelled();
    }
}

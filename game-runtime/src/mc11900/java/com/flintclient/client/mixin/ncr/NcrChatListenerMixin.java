package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports in chat: the server's "needs a signature" notice handled by the signing mode. Minecraft 1.19 (1.19.0) has no
 * ChatListener (1.19.1 brought it): the server's system messages reach the HUD's handleSystemChat, with their chat type (one with
 * no chat display is the action bar's). It marks no message as unverified or modified either (1.19.1 brought trust levels and
 * their grey bars), so there are no indicators to hide. (1.19.0's servers don't send the "needs a signature" notice: they turn a
 * client without a key away at login, compat.net.LoginSigning. The hook stays for servers that do.)
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(Gui.class)
abstract class NcrChatListenerMixin {
	@Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
	private void flint$signingRequested(ChatType type, Component message, CallbackInfo callback) {
		boolean overlay = type.chat().isEmpty();
		if (Mc263ChatSafety.systemMessage(message, overlay, (shown, onBar) -> ((Gui) (Object) this).handleSystemChat(type, shown))) {
			callback.cancel();
		}
	}
}

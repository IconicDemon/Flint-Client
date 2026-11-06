package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.multiplayer.chat.ChatTrustLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports in chat: the server's "needs a signature" notice handled by the signing mode, and the trust indicators
 * (grey bars) of unverified and modified messages hidden.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ChatListener.class)
abstract class NcrChatListenerMixin {
	@Shadow
	private boolean isSenderLocalPlayer(UUID sender) {
		throw new AssertionError();
	}

	@Shadow
	public abstract void handleSystemMessage(Component message, boolean overlay);

	@Inject(method = "handleSystemMessage", at = @At("HEAD"), cancellable = true)
	private void flint$signingRequested(Component message, boolean overlay, CallbackInfo callback) {
		if (Mc263ChatSafety.systemMessage(message, overlay, this::handleSystemMessage)) {
			callback.cancel();
		}
	}

	@Inject(method = "evaluateTrustLevel", at = @At("HEAD"), cancellable = true)
	private void flint$trust(PlayerChatMessage message, Component decorated, Instant received, CallbackInfoReturnable<ChatTrustLevel> callback) {
		ChatTrustLevel level = Mc263ChatSafety.trust(message, decorated, received, isSenderLocalPlayer(message.sender()));
		if (level != null) {
			callback.setReturnValue(level);
		}
	}
}

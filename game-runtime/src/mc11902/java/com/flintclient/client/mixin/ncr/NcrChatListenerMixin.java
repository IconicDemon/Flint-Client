package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.multiplayer.chat.ChatTrustLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports in chat: the server's "needs a signature" notice handled by the signing mode, and the trust indicators
 * (grey bars) of unverified and modified messages hidden. Minecraft 1.19.2 works a message's trust out from its sender's chain
 * of signed messages (each check moves the chain on), so the level Minecraft found is taken as it returns and changed there,
 * rather than worked out a second time. (1.19.2's servers don't send the "needs a signature" notice: they turn a client without
 * a key away at login, compat.net.LoginSigning. The hook stays for servers that do.)
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

	@Inject(method = "evaluateTrustLevel", at = @At("RETURN"), cancellable = true)
	private void flint$trust(PlayerChatMessage message, Component decorated, @Nullable PlayerInfo sender, Instant received,
		CallbackInfoReturnable<ChatTrustLevel> callback) {
		ChatTrustLevel level = Mc263ChatSafety.trust(message, decorated, callback.getReturnValue(), isSenderLocalPlayer(message.signer().profileId()));
		if (level != null) {
			callback.setReturnValue(level);
		}
	}
}

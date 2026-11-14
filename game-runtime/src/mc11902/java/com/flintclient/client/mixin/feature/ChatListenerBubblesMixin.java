package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSigner;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags' chat bubbles: who said what in chat, as each message arrives. Minecraft 1.19.2 gets every chat message through
 * handleChatMessage; one with the system as its signer (a server's /say) is 1.19.3's disguised chat. The message's content is
 * the server's (decorated) one when it sent one, as later versions' decoratedContent. handleSystemMessage's flag is the action
 * bar's on 1.19.2: those are the server's own notices, never someone's chat.
 */
@Mixin(ChatListener.class)
abstract class ChatListenerBubblesMixin {
	@Inject(method = "handleChatMessage", at = @At("HEAD"))
	private void flint$playerChat(PlayerChatMessage message, ChatType.Bound bound, CallbackInfo callback) {
		MessageSigner signer = message.signer();
		if (signer.isSystem()) {
			Mc263Nametags.disguisedChat(message.serverContent(), bound);
		} else {
			Mc263Nametags.playerChat(signer.profileId(), message.serverContent(), bound);
		}
	}

	@Inject(method = "handleSystemMessage", at = @At("HEAD"))
	private void flint$systemChat(Component message, boolean overlay, CallbackInfo callback) {
		// The action bar never reads as someone's chat.
		if (!overlay) {
			Mc263Nametags.systemChat(message);
		}
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nametags' chat bubbles: who said what in chat, as each message arrives. */
@Mixin(ChatListener.class)
abstract class ChatListenerBubblesMixin {
	@Inject(method = "handlePlayerChatMessage", at = @At("HEAD"))
	private void flint$playerChat(PlayerChatMessage message, GameProfile sender, ChatType.Bound bound, CallbackInfo callback) {
		Mc263Nametags.playerChat(sender.id(), message.decoratedContent(), bound);
	}

	@Inject(method = "handleDisguisedChatMessage", at = @At("HEAD"))
	private void flint$disguisedChat(Component message, ChatType.Bound bound, CallbackInfo callback) {
		Mc263Nametags.disguisedChat(message, bound);
	}

	@Inject(method = "handleSystemMessage", at = @At("HEAD"))
	private void flint$systemChat(Component message, boolean fromServer, CallbackInfo callback) {
		// The client's own notices (command feedback it makes, Flint's lines) never read as someone's chat.
		if (fromServer) {
			Mc263Nametags.systemChat(message);
		}
	}
}

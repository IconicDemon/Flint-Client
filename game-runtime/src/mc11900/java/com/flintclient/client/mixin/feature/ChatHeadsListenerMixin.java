package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.v1190.ChatSenders;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.ChatSender;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat Heads: what kind of message the chat is being given, and who the server says sent it, while it is shown. The chat's own
 * hook (Mc263Chat.incoming) puts the head in. Minecraft 1.19 (1.19.0) has no ChatListener (1.19.1 brought it): the HUD's
 * handlePlayerChat hands a player's message to the chat window with its sender (the nil UUID for a message with no player
 * behind it, a server's /say: 1.19.3's disguised chat), and handleSystemChat the server's own messages (those of a type with no
 * chat display go to the action bar only). Who sent a message the chat holds back for Chat Delay goes with it
 * ({@link ChatSenders}, mixin.feature.ChatComponentMixin).
 */
@Mixin(Gui.class)
abstract class ChatHeadsListenerMixin {
	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void flint$playerMessage(ChatType type, Component message, ChatSender sender, CallbackInfo callback) {
		ChatSenders.player(sender);
	}

	@Inject(method = "handlePlayerChat", at = @At("RETURN"))
	private void flint$playerMessageDone(CallbackInfo callback) {
		ChatSenders.done();
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void flint$systemMessage(ChatType type, Component message, CallbackInfo callback) {
		ChatSenders.system(type.chat().isPresent());
	}

	@Inject(method = "handleSystemChat", at = @At("RETURN"))
	private void flint$systemMessageDone(CallbackInfo callback) {
		ChatSenders.done();
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.v1190.ChatBound;
import com.flintclient.client.control.platform.Mc263Nametags;
import net.minecraft.Util;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.ChatSender;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags' chat bubbles: who said what in chat, as each message arrives. Minecraft 1.19 (1.19.0) has no ChatListener (1.19.1
 * brought it): the HUD's handlePlayerChat gets every player chat message, with its content (the signed or the server's, as the
 * Only Show Secure Chat option picks) and its sender; one with the nil UUID as its sender (a server's /say) is 1.19.3's disguised
 * chat. handleSystemChat gets the server's own notices: those of a type with no chat display are the action bar's, never
 * someone's chat.
 */
@Mixin(Gui.class)
abstract class ChatListenerBubblesMixin {
	@Inject(method = "handlePlayerChat", at = @At("HEAD"))
	private void flint$playerChat(ChatType type, Component message, ChatSender sender, CallbackInfo callback) {
		ChatBound bound = new ChatBound(type, sender.name());
		if (Util.NIL_UUID.equals(sender.uuid())) {
			Mc263Nametags.disguisedChat(message, bound);
		} else {
			Mc263Nametags.playerChat(sender.uuid(), message, bound);
		}
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void flint$systemChat(ChatType type, Component message, CallbackInfo callback) {
		// The action bar never reads as someone's chat.
		if (type.chat().isPresent()) {
			Mc263Nametags.systemChat(message);
		}
	}
}

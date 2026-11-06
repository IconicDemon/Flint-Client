package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Heads: what kind of message the chat is being given, and who the server says sent it, while ChatListener shows it
 * (now or from its delay queue). The chat's own hook (Mc263Chat.incoming) puts the head in.
 */
@Mixin(ChatListener.class)
abstract class ChatHeadsListenerMixin {
	@Inject(method = "showMessageToPlayer", at = @At("HEAD"))
	private void flint$playerMessage(ChatType.Bound bound, PlayerChatMessage message, Component decorated, GameProfile sender, boolean onlyShowSecure,
		Instant received, CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.playerMessage(sender);
	}

	@Inject(method = "showMessageToPlayer", at = @At("RETURN"))
	private void flint$playerMessageDone(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.messageDone();
	}

	@Inject(method = McCompat.DISGUISED_SHOW, at = @At("HEAD"))
	private void flint$disguisedMessage(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.disguisedMessage();
	}

	@Inject(method = McCompat.DISGUISED_SHOW, at = @At("RETURN"))
	private void flint$disguisedMessageDone(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.messageDone();
	}

	@Inject(method = "handleSystemMessage", at = @At("HEAD"))
	private void flint$systemMessage(Component message, boolean flag, CallbackInfo callback) {
		Mc263ChatHeads.systemMessage(McCompat.systemFromServer(flag));
	}

	@Inject(method = "handleSystemMessage", at = @At("RETURN"))
	private void flint$systemMessageDone(CallbackInfo callback) {
		Mc263ChatHeads.messageDone();
	}
}

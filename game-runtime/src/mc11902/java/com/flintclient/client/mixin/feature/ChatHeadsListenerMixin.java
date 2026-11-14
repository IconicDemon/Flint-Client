package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Heads: what kind of message the chat is being given, and who the server says sent it, while ChatListener shows it
 * (now or from its delay queue). The chat's own hook (Mc263Chat.incoming) puts the head in. Minecraft 1.19.2 hands the player
 * message its sender's tab-list entry (null when the sender isn't listed: then the message's own signer and name), shows a
 * message with no player behind it (a server's /say: 1.19.3's disguised chat) through processNonPlayerChatMessage, and flags
 * a system message for the action bar rather than as the server's.
 */
@Mixin(ChatListener.class)
abstract class ChatHeadsListenerMixin {
	@Inject(method = "showMessageToPlayer", at = @At("HEAD"))
	private void flint$playerMessage(ChatType.Bound bound, PlayerChatMessage message, Component decorated, @Nullable PlayerInfo sender,
		boolean onlyShowSecure, Instant received, CallbackInfoReturnable<Boolean> callback) {
		GameProfile profile = sender != null ? sender.getProfile() : new GameProfile(message.signer().profileId(), bound.name().getString());
		Mc263ChatHeads.playerMessage(profile);
	}

	@Inject(method = "showMessageToPlayer", at = @At("RETURN"))
	private void flint$playerMessageDone(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.messageDone();
	}

	@Inject(method = "processNonPlayerChatMessage", at = @At("HEAD"))
	private void flint$disguisedMessage(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.disguisedMessage();
	}

	@Inject(method = "processNonPlayerChatMessage", at = @At("RETURN"))
	private void flint$disguisedMessageDone(CallbackInfoReturnable<Boolean> callback) {
		Mc263ChatHeads.messageDone();
	}

	@Inject(method = "handleSystemMessage", at = @At("HEAD"))
	private void flint$systemMessage(Component message, boolean overlay, CallbackInfo callback) {
		Mc263ChatHeads.systemMessage(!overlay);
	}

	@Inject(method = "handleSystemMessage", at = @At("RETURN"))
	private void flint$systemMessageDone(CallbackInfo callback) {
		Mc263ChatHeads.messageDone();
	}
}

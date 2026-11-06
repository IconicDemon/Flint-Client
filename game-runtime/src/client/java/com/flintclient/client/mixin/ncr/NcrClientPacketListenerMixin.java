package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.LocalChatSession;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports on the play connection: the join (how safe the server is), leaving (the state starts over), and what
 * signing needs (the chat session, setting a key, whether a command has signable arguments). handleLogin first runs on the
 * Netty thread and hands itself to the main thread, so its end runs once, on the main thread.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ClientPacketListener.class)
abstract class NcrClientPacketListenerMixin implements Mc263ChatSafety.Session {
	@Shadow
	private @Nullable LocalChatSession chatSession;
	@Shadow
	private CommandDispatcher<ClientSuggestionProvider> commands;
	@Shadow
	@Final
	private ClientSuggestionProvider suggestionsProvider;

	@Shadow
	private void setKeyPair(ProfileKeyPair keyPair) {
		throw new AssertionError();
	}

	@Override
	public @Nullable Object flint$chatSession() {
		return chatSession;
	}

	@Override
	public void flint$setKeyPair(ProfileKeyPair pair) {
		setKeyPair(pair);
	}

	@Override
	public boolean flint$signableArguments(String command) {
		return !SignableCommand.of(commands.parse(command, suggestionsProvider)).arguments().isEmpty();
	}

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void flint$joined(CallbackInfo callback) {
		Mc263ChatSafety.joined((ClientPacketListener) (Object) this);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void flint$left(CallbackInfo callback) {
		Mc263ChatSafety.left();
	}
}

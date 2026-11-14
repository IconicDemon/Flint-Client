package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.net.LoginSigning;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ArgumentSignatures;
import net.minecraft.network.chat.PreviewableCommand;
import net.minecraft.world.entity.player.ProfileKeyPair;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports on the play connection (Minecraft 1.19.2): the join (how safe the server is), leaving (the state starts over,
 * and so does the connection's key state), and what signing needs. 1.19.2 has no chat sessions: the key this session signs with
 * is the one the login gave the server (your player's own, none when it was kept back), and it can't change until the next join,
 * so setting a key does nothing here. handleLogin first runs on the Netty thread and hands itself to the main thread, so its end
 * runs once, on the main thread; cleanup is 1.19.2's close, as the world is left.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ClientPacketListener.class)
abstract class NcrClientPacketListenerMixin implements Mc263ChatSafety.Session {
	@Shadow
	@Final
	private Minecraft minecraft;
	@Shadow
	private CommandDispatcher<SharedSuggestionProvider> commands;
	@Shadow
	@Final
	private ClientSuggestionProvider suggestionsProvider;

	@Override
	public @Nullable Object flint$chatSession() {
		LocalPlayer player = minecraft.player;
		return player != null ? player.getProfilePublicKey() : null;
	}

	@Override
	public void flint$setKeyPair(ProfileKeyPair pair) {
		// The server learns a key at login only (compat.net.LoginSigning): signing allowed now starts with the next join.
	}

	@Override
	public boolean flint$signableArguments(String command) {
		return ArgumentSignatures.hasSignableArguments(PreviewableCommand.of(commands.parse(command, suggestionsProvider)));
	}

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void flint$joined(CallbackInfo callback) {
		Mc263ChatSafety.joined((ClientPacketListener) (Object) this);
	}

	@Inject(method = "cleanup", at = @At("HEAD"))
	private void flint$left(CallbackInfo callback) {
		Mc263ChatSafety.left();
		LoginSigning.end();
	}
}

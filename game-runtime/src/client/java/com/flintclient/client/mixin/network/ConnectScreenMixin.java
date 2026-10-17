package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import com.llamalad7.mixinextras.injector.ModifyReceiver;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Starts tracking a multiplayer attempt where {@code ConnectScreen.connect} (main thread) starts its
 * "Server Connector" thread, so the tracker knows that exact thread before it runs: lookups and socket
 * connects on any other thread (server-list pings, Realms, an older attempt's thread) are then ignored.
 * {@code startConnecting} isn't used because it can return early. The thread is passed through unchanged.
 */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
	@ModifyReceiver(method = "connect", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"))
	private Thread flint$attemptStarting(Thread connector) {
		NetworkHooks.attemptStarted(connector);
		return connector;
	}
}

package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Play, main thread. handleLogin and handleConfigurationStart first run on the Netty thread and throw to
 * hand the packet to the main thread, so a hook at their start would fire twice; at their end it fires
 * once, on the main thread. close() is the only signal for quitting: nothing ticks the connection after
 * that, so no onDisconnect follows.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void flint$playStarted(CallbackInfo callback) {
		NetworkHooks.playStarted(((ClientPacketListener) (Object) this).getConnection());
	}

	@Inject(method = "handleConfigurationStart", at = @At("TAIL"))
	private void flint$reconfiguring(CallbackInfo callback) {
		NetworkHooks.reconfiguring(((ClientPacketListener) (Object) this).getConnection());
	}

	/** The server's game time, about once a second (on the main thread at the end): the TPS module's measure. */
	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void flint$serverTime(net.minecraft.network.protocol.game.ClientboundSetTimePacket packet, CallbackInfo callback) {
		com.flintclient.client.control.feature.Features.SERVER_TICKS.record(packet.gameTime(), System.nanoTime());
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void flint$closed(CallbackInfo callback) {
		NetworkHooks.closed(((ClientPacketListener) (Object) this).getConnection());
	}
}

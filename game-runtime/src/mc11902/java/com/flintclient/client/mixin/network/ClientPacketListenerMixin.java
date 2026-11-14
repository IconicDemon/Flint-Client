package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Play, main thread (Minecraft 1.19.2). handleLogin first runs on the Netty thread and throws to hand the packet to the main
 * thread, so a hook at its start would fire twice; at its end it fires once, on the main thread. 1.19.2 has no configuration
 * phase, so play starts once per connection and is never reconfigured. cleanup() is 1.19.2's close(): the only signal for
 * quitting (Minecraft calls it as it leaves the world); nothing ticks the connection after that, so no onDisconnect follows.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void flint$playStarted(CallbackInfo callback) {
		NetworkHooks.playStarted(((ClientPacketListener) (Object) this).getConnection());
	}

	/** The server's game time, about once a second (on the main thread at the end): the TPS module's measure. */
	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void flint$serverTime(net.minecraft.network.protocol.game.ClientboundSetTimePacket packet, CallbackInfo callback) {
		com.flintclient.client.control.feature.Features.SERVER_TICKS.record(packet.getGameTime(), System.nanoTime());
	}

	@Inject(method = "cleanup", at = @At("HEAD"))
	private void flint$closed(CallbackInfo callback) {
		NetworkHooks.closed(((ClientPacketListener) (Object) this).getConnection());
	}
}

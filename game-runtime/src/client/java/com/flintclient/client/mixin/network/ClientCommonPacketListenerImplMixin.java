package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Configuration and play, which share these two. handleDisconnect (the server's disconnect packet) runs
 * on the Netty thread and never passes to the main thread; onDisconnect runs on the main thread, and the
 * configuration listener's override calls this one first. Once per connection each.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
	@Shadow
	@Final
	protected Connection connection;

	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void flint$kicked(ClientboundDisconnectPacket packet, CallbackInfo callback) {
		NetworkHooks.kicked(connection, packet.reason());
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void flint$disconnected(DisconnectionDetails details, CallbackInfo callback) {
		NetworkHooks.disconnected(connection, details);
	}
}

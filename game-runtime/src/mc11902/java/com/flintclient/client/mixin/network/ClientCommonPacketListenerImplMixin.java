package com.flintclient.client.mixin.network;

import com.flintclient.client.compat.net.DisconnectionDetails;
import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Play's disconnects on Minecraft 1.19.2, which has no configuration phase (ClientCommonPacketListenerImpl, the listener 1.20.2
 * shares between configuration and play, isn't there: play's own listener has both). handleDisconnect (the server's disconnect
 * packet) runs on the Netty thread and never passes to the main thread; onDisconnect runs on the main thread with the reason
 * (1.19.2's disconnects carry nothing else). Once per connection each.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientCommonPacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void flint$kicked(ClientboundDisconnectPacket packet, CallbackInfo callback) {
		NetworkHooks.kicked(connection, packet.getReason());
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void flint$disconnected(Component reason, CallbackInfo callback) {
		NetworkHooks.disconnected(connection, new DisconnectionDetails(reason));
	}
}

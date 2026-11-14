package com.flintclient.client.mixin.network;

import com.flintclient.client.compat.net.DisconnectionDetails;
import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Login on Minecraft 1.19.2. None of these handlers hands its packet to the main thread, so each runs once, on the Netty thread;
 * onDisconnect runs on the main thread when the connect screen ticks a closed connection. Once per connection each, so plain
 * callbacks are fine. 1.19.2's server sends its hello only in online mode (it always wants a session check then); the session
 * check runs on the download pool in handleHello's lambda, which then sends the key packet that starts encryption (1.19.2 has
 * no setEncryption): that send is where authentication is over. handleGameProfile finishes the login (as on 1.21.1).
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleHello", at = @At("HEAD"))
	private void flint$hello(CallbackInfo callback) {
		NetworkHooks.hello(connection, true);
	}

	@Inject(method = "method_2894", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"))
	private void flint$encrypting(CallbackInfo callback) {
		NetworkHooks.encrypting(connection);
	}

	@Inject(method = "handleGameProfile", at = @At("HEAD"))
	private void flint$loginFinished(CallbackInfo callback) {
		NetworkHooks.loginFinished(connection);
	}

	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void flint$kicked(ClientboundLoginDisconnectPacket packet, CallbackInfo callback) {
		NetworkHooks.kicked(connection, packet.getReason());
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void flint$disconnected(Component reason, CallbackInfo callback) {
		NetworkHooks.disconnected(connection, new DisconnectionDetails(reason));
	}
}

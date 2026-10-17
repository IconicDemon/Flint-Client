package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Login. None of these handlers hands its packet to the main thread, so each runs once, on the Netty
 * thread (setEncryption on the I/O pool after a session check); onDisconnect runs on the main thread when
 * the connect screen ticks a closed connection. Once per connection each, so plain callbacks are fine.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "handleHello", at = @At("HEAD"))
	private void flint$hello(ClientboundHelloPacket packet, CallbackInfo callback) {
		NetworkHooks.hello(connection, packet.shouldAuthenticate());
	}

	@Inject(method = "setEncryption", at = @At("HEAD"))
	private void flint$encrypting(CallbackInfo callback) {
		NetworkHooks.encrypting(connection);
	}

	@Inject(method = "handleLoginFinished", at = @At("HEAD"))
	private void flint$loginFinished(CallbackInfo callback) {
		NetworkHooks.loginFinished(connection);
	}

	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void flint$kicked(ClientboundLoginDisconnectPacket packet, CallbackInfo callback) {
		NetworkHooks.kicked(connection, packet.reason());
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void flint$disconnected(DisconnectionDetails details, CallbackInfo callback) {
		NetworkHooks.disconnected(connection, details);
	}
}

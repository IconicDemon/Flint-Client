package com.flintclient.client.mixin.network;

import com.flintclient.client.network.platform.NetworkHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The socket and the disconnect of a remote connection. {@code connect} has started the socket by the time
 * it returns; the hook records which Connection the tracked attempt opened and listens to the returned
 * future (the bootstrap, pipeline and future are left as they are). {@code disconnect} runs on any thread
 * for every connection, singleplayer's and the integrated server's included, so both hooks pass values
 * through instead of allocating callback info, and the tracker drops untracked connections after one
 * identity check.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {
	@ModifyReturnValue(
		method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
		at = @At("RETURN"))
	private static ChannelFuture flint$socketOpening(ChannelFuture future, InetSocketAddress address, EventLoopGroupHolder group, Connection connection) {
		NetworkHooks.socketOpening(connection, future);
		return future;
	}

	@ModifyVariable(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"), argsOnly = true)
	private DisconnectionDetails flint$disconnecting(DisconnectionDetails details) {
		NetworkHooks.disconnecting((Connection) (Object) this, details);
		return details;
	}
}

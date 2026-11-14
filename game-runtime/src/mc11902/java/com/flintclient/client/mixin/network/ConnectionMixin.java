package com.flintclient.client.mixin.network;

import com.flintclient.client.compat.net.DisconnectionDetails;
import com.flintclient.client.network.platform.NetworkHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The socket and the disconnect of a remote connection (Minecraft 1.19.2). 1.19.2's {@code connectToServer} builds the
 * connection, starts its socket and waits for it there: the hook takes the started socket's future as the bootstrap hands it
 * over (before the wait), records which Connection the tracked attempt opened and listens to the future (the bootstrap, pipeline
 * and future are left as they are). {@code disconnect} runs on any thread for every connection, singleplayer's and the
 * integrated server's included (with only a reason on 1.19.2), and the tracker drops untracked connections after one identity
 * check.
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {
	@ModifyExpressionValue(method = "connectToServer", at = @At(value = "INVOKE", remap = false,
		target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;"))
	private static ChannelFuture flint$socketOpening(ChannelFuture future, @Local Connection connection) {
		NetworkHooks.socketOpening(connection, future);
		com.flintclient.client.network.platform.NetworkTuning.socketOpening(future);
		return future;
	}

	@Inject(method = "disconnect", at = @At("HEAD"))
	private void flint$disconnecting(Component reason, CallbackInfo callback) {
		NetworkHooks.disconnecting((Connection) (Object) this, new DisconnectionDetails(reason));
	}
}

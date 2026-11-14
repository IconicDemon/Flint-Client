package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports' "convert to game message" in the world this client hosts (Minecraft 1.19 (1.19.0)): player chat goes to
 * players as system messages, which carry no signature. 1.19.0's play listener sends its own packets (ServerCommonPacketListenerImpl
 * came in 1.20.2), every one through send(packet, listener) (send(packet) passes no listener), so that is where it is converted,
 * keeping the listener. The world drops players' keys (NcrChatSessionUpdateMixin), so what it relays carries no signature anyway.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class NcrServerPacketListenerMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/util/concurrent/GenericFutureListener;)V", at = @At("HEAD"),
		cancellable = true)
	private void flint$convert(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> listener, CallbackInfo callback) {
		Packet<?> system = Mc263ChatSafety.outgoing(packet);
		if (system != null) {
			callback.cancel();
			((ServerGamePacketListenerImpl) (Object) this).send(system, listener);
		}
	}
}

package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports' "convert to game message" in the world this client hosts: player chat goes to players as system
 * messages, which carry no signature. A chat packet sent with a listener goes the plain way, so it is converted too.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class NcrServerPacketListenerMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void flint$convert(Packet<?> packet, CallbackInfo callback) {
		if ((Object) this instanceof ServerGamePacketListenerImpl listener) {
			Packet<?> system = Mc263ChatSafety.outgoing(packet);
			if (system != null) {
				callback.cancel();
				// The player never sees this player chat index: the next real one takes it.
				((NcrServerGameListenerAccessor) listener).flint$setNextChatIndex(((ClientboundPlayerChatPacket) packet).globalIndex());
				listener.send(system);
			}
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
	private void flint$convertWithListener(Packet<?> packet, @Nullable ChannelFutureListener listener, CallbackInfo callback) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game && listener != null && packet instanceof ClientboundPlayerChatPacket
			&& Mc263ChatSafety.outgoing(packet) != null) {
			callback.cancel();
			game.send(packet);
		}
	}
}

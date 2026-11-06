package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.network.PacketSendListener;
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
 * No Chat Reports' "convert to game message" in the world this client hosts (Minecraft 1.21.1): player chat goes to players
 * as system messages, which carry no signature. A chat packet sent with a listener goes the plain way, so it is converted
 * too. (1.21.1 numbers player chat per sender inside the signed chain, which a system message doesn't touch: no index is given
 * back, as 1.21.5's global one is on the later versions.)
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
				listener.send(system);
			}
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
	private void flint$convertWithListener(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo callback) {
		if ((Object) this instanceof ServerGamePacketListenerImpl game && listener != null && packet instanceof ClientboundPlayerChatPacket
			&& Mc263ChatSafety.outgoing(packet) != null) {
			callback.cancel();
			game.send(packet);
		}
	}
}

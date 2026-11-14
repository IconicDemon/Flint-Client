package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Chat Reports' "convert to game message" in the world this client hosts (Minecraft 1.19.2): player chat goes to players as
 * system messages, which carry no signature. 1.19.2's play listener sends its own packets (ServerCommonPacketListenerImpl came in
 * 1.20.2), and sends tracked player chat with a listener (the header it falls back to); that goes the plain way, so it is
 * converted too. The world drops players' keys (NcrChatSessionUpdateMixin), so what it relays carries no signature to track:
 * nothing waits for players to acknowledge it.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class NcrServerPacketListenerMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void flint$convert(Packet<?> packet, CallbackInfo callback) {
		Packet<?> system = Mc263ChatSafety.outgoing(packet);
		if (system != null) {
			callback.cancel();
			((ServerGamePacketListenerImpl) (Object) this).send(system);
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
	private void flint$convertWithListener(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo callback) {
		if (listener != null && packet instanceof ClientboundPlayerChatPacket && Mc263ChatSafety.outgoing(packet) != null) {
			callback.cancel();
			((ServerGamePacketListenerImpl) (Object) this).send(packet);
		}
	}
}

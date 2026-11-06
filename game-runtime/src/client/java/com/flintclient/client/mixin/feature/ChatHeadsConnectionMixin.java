package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat Heads: joining a server forgets what the last one said (whether it names senders, whether it opted out). */
@Mixin(Connection.class)
abstract class ChatHeadsConnectionMixin {
	@Inject(method = {
		"initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/protocol/login/ClientLoginPacketListener;)V",
		"initiateServerboundPlayConnection(Ljava/lang/String;ILnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/ClientboundPacketListener;Z)V"},
		at = @At("HEAD"))
	private void flint$newServer(CallbackInfo callback) {
		Mc263ChatHeads.connecting();
	}
}

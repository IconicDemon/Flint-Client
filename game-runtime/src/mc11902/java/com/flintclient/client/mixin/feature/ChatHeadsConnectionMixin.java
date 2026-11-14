package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chat Heads: joining a server forgets what the last one said (whether it names senders, whether it opted out). Minecraft
 * 1.19.2 has no initiateServerboundPlayConnection: each login (a server, a Realm, a world of your own) makes its login listener.
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ChatHeadsConnectionMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$newServer(CallbackInfo callback) {
		Mc263ChatHeads.connecting();
	}
}

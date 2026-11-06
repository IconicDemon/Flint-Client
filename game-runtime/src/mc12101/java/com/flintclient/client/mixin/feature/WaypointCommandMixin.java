package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263WaypointShare;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Waypoints shared in chat: clicking one adds it here instead of sending its command to the server. Minecraft 1.21.1 runs a
 * clicked chat command through sendUnsignedCommand.
 */
@Mixin(ClientPacketListener.class)
abstract class WaypointCommandMixin {
	@Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
	private void flint$waypointCommand(String command, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263WaypointShare.command(command)) {
			callback.setReturnValue(true);
		}
	}
}

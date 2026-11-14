package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263WaypointShare;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Waypoints shared in chat: clicking one adds it here instead of sending its command to the server. Minecraft 1.19.2 runs a
 * clicked chat command through the player's commandUnsigned (the command without its slash; true when it went).
 */
@Mixin(LocalPlayer.class)
abstract class WaypointCommandMixin {
	@Inject(method = "commandUnsigned", at = @At("HEAD"), cancellable = true)
	private void flint$waypointCommand(String command, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263WaypointShare.command(command)) {
			callback.setReturnValue(true);
		}
	}
}

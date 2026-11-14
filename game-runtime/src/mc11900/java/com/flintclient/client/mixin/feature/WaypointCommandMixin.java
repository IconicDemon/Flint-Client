package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263WaypointShare;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waypoints shared in chat: clicking one adds it here instead of sending its command to the server. Minecraft 1.19 (1.19.0) runs
 * a clicked chat command through the player's command(String) (the command without its slash, signed with no preview), where
 * 1.19.1 has commandUnsigned; what you type, and Flint's own commands (McCompat.sendCommand), go through command(String, preview).
 */
@Mixin(LocalPlayer.class)
abstract class WaypointCommandMixin {
	@Inject(method = "command(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
	private void flint$waypointCommand(String command, CallbackInfo callback) {
		if (Mc263WaypointShare.command(command)) {
			callback.cancel();
		}
	}
}

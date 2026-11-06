package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263WaypointShare;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waypoints shared in chat: clicking one's "Add" runs a command (as every chat click does); Flint answers its own command
 * here and nothing reaches the server.
 */
@Mixin(ClientPacketListener.class)
abstract class WaypointCommandMixin {
	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void flint$waypointCommand(String command, Screen screen, CallbackInfo callback) {
		if (Mc263WaypointShare.command(command)) {
			callback.cancel();
		}
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263GameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server leave confirmation: the pause menu's Disconnect button asks first on a server (No returns to this menu). */
@Mixin(PauseScreen.class)
abstract class PauseScreenLeaveMixin {
	// The Disconnect button's action in createPauseMenu.
	@Inject(method = com.flintclient.client.compat.McCompat.PAUSE_DISCONNECT, at = @At("HEAD"), cancellable = true)
	private void flint$confirmLeave(Button button, CallbackInfo callback) {
		if (Mc263GameOptions.confirmsLeaving()) {
			callback.cancel();
			Mc263GameOptions.leave(Minecraft.getInstance(), (PauseScreen) (Object) this);
		}
	}
}

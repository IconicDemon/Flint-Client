package com.flintclient.qa.mixin;

import com.flintclient.qa.FlintQa;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During a tour, only the tour clicks (Minecraft 1.21.1: onPress). The game window runs behind other windows, so a real click
 * on it would otherwise reach the screen under test as if the tour had made it. "mouse" steps go through this handler and
 * are let through.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onPress", at = @At("HEAD"), cancellable = true, require = 1)
	private void flintqa$tourClicksOnly(long window, int button, int action, int modifiers, CallbackInfo ci) {
		if (FlintQa.ENABLED && !FlintQa.tourClick) {
			FlintQa.log("ignored a real mouse button " + button + (action == 1 ? " press" : " release") + " (not the tour's)");
			ci.cancel();
		}
	}
}

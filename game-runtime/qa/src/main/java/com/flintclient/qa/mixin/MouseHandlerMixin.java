package com.flintclient.qa.mixin;

import com.flintclient.qa.FlintQa;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * During a tour, only the tour clicks. The game window runs behind other windows, so a real click on it (someone using the
 * machine) would otherwise reach the screen under test as if the tour had made it: a right-click opened a menu on the
 * world map in a 26.2 run whose script never clicked there. Tour clicks on screens call the screen directly; "mouse"
 * steps go through this handler and are let through.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true, require = 1)
	private void flintqa$tourClicksOnly(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (FlintQa.ENABLED && !FlintQa.tourClick) {
			FlintQa.log("ignored a real mouse button " + info.button() + (action == 1 ? " press" : " release") + " (not the tour's)");
			ci.cancel();
		}
	}
}

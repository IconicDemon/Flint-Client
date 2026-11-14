package com.flintclient.legacy.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.render.ProgressRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap: 1.8.9 draws its saving and loading screens straight to the window (not through the game loop); while the old
 * game holds its last frame (leaving its world under it), they aren't drawn.
 */
@Mixin(ProgressRenderer.class)
abstract class SwapProgressMixin {
	@Inject(method = "progressStagePercentage", at = @At("HEAD"), cancellable = true)
	private void flint$held(int percentage, CallbackInfo ci) {
		if (InstantSwap.framesHeld()) {
			ci.cancel();
		}
	}
}

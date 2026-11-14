package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap ({@link InstantSwap#framesHeld}): no frame is drawn or presented while the game hands over (the window keeps
 * showing its last frame, so leaving the world or server under it is never seen) or while a standby game waits hidden at its
 * menu (it costs the player's game no GPU time). Ticks, input and the world's saving go on as usual.
 */
@Mixin(Minecraft.class)
abstract class SwapFrameMixin {
	@Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
	private void flint$holdFrame(boolean renderLevel, CallbackInfo callback) {
		if (InstantSwap.framesHeld()) {
			callback.cancel();
		}
	}

	/**
	 * A frame really went to the screen (the window surface's present, which Minecraft skips when it has no surface texture):
	 * the new game counts these before it lets the still go, so the player never sees its window without a picture.
	 */
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
	private void flint$framePresented(boolean renderLevel, CallbackInfo callback) {
		InstantSwap.framePresented();
	}
}

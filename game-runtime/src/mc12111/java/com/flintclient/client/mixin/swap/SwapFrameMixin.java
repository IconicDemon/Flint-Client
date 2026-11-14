package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Instant Swap ({@link InstantSwap#framesHeld}): no frame is drawn while the game hands over (the window keeps showing its last
 * frame, so leaving the world or server under it is never seen) or while a standby game waits hidden at its menu (it costs the
 * player's game no GPU time). On 1.21.x one loop does the ticks and the frame, so only the frame's drawing is left out here, and
 * its presenting in {@link SwapPresentMixin}; ticks, input and the world's saving go on as usual.
 */
@Mixin(Minecraft.class)
abstract class SwapFrameMixin {
	@WrapWithCondition(method = "runTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
	private boolean flint$holdFrame(GameRenderer renderer, DeltaTracker delta, boolean renderLevel) {
		return !InstantSwap.framesHeld();
	}
}

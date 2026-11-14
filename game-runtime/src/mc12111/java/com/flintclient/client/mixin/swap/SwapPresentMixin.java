package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap ({@link InstantSwap#framesHeld}): while frames are held the window's buffers aren't swapped, so the last frame
 * presented stays on screen (a frame not drawn, {@link SwapFrameMixin}, is never shown). The window's events are still taken
 * (the flip polls them before and after), so the game keeps answering the system. A swap that did happen is a frame that really
 * went to the screen: the new game counts these before it lets the still go ({@link InstantSwap#framePresented}).
 */
@Mixin(RenderSystem.class)
abstract class SwapPresentMixin {
	@WrapWithCondition(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
	private static boolean flint$holdPresent(long window) {
		return !InstantSwap.framesHeld();
	}

	@Inject(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false,
		shift = At.Shift.AFTER))
	private static void flint$framePresented(CallbackInfo callback) {
		if (!InstantSwap.framesHeld()) {
			if (InstantSwap.comingIn()) {
				// Counted once Windows has composed it (on screen for real), not merely handed to the driver.
				com.flintclient.client.control.platform.Mc263SwapEngine.waitComposed();
			}
			InstantSwap.framePresented();
		}
	}
}

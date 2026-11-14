package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap's standby game ({@link InstantSwap}): its window is made hidden (GLFW_VISIBLE off for the window Minecraft makes),
 * and windowed even when the player plays fullscreen, so it never takes the screen or a display mode while the player's game
 * runs. It shows itself, in the old window's place and mode, when it takes over. Any other game is untouched.
 */
@Mixin(Window.class)
abstract class SwapWindowMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
		remap = false))
	private void flint$hiddenWhileStandby(CallbackInfo callback) {
		if (InstantSwap.standby()) {
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		}
	}

	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static DisplayData flint$windowedWhileStandby(DisplayData data) {
		return InstantSwap.standby() && data.isFullscreen() ? data.withFullscreen(false) : data;
	}
}

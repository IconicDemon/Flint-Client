package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap's standby game ({@link InstantSwap}) on Minecraft 1.19: its window is made hidden (GLFW_VISIBLE off), and
 * windowed even when the player plays fullscreen, so it never takes the screen or a display mode while the player's game runs
 * (SwapFullscreenMixin keeps Minecraft from going fullscreen after). It shows itself, in the old window's place and mode, when
 * it takes over. Any other game is untouched.
 */
@Mixin(Window.class)
abstract class SwapWindowMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private void flint$hiddenWhileStandby(WindowEventHandler handler, ScreenManager screens, DisplayData data, String videoMode, String title,
		CallbackInfo callback) {
		if (InstantSwap.standby()) {
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		}
	}

	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static DisplayData flint$windowedWhileStandby(DisplayData data) {
		return InstantSwap.standby() && data.isFullscreen
			? new DisplayData(data.width, data.height, data.fullscreenWidth, data.fullscreenHeight, false) : data;
	}
}

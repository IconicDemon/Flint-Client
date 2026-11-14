package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.21.x: Flint's Borderless fullscreen. With it on, Minecraft's fullscreen (F11, Video Settings) is an undecorated window over
 * the whole monitor at the desktop's resolution, as 26.x's own non-exclusive fullscreen: Alt+Tab is instant, overlays work,
 * and Windows composes the game like any window, so a screen capture (Discord, OBS) gets every frame. Like Minecraft 26.x it
 * is one pixel wider than the monitor, so Windows doesn't promote it to exclusive presentation (where captures can stall).
 * On by default; off: Minecraft's exclusive fullscreen, unchanged. Only GLFW calls: the same on 1.21.1, 1.21.10 and 1.21.11.
 */
@Mixin(Window.class)
abstract class FullscreenModeMixin {
	@Unique
	private boolean flint$borderless;

	/** While Flint's borderless window is up it counts as fullscreen, so switching again doesn't take it for the windowed size. */
	@Redirect(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwGetWindowMonitor(J)J", remap = false))
	private long flint$monitorNow(long handle) {
		return flint$borderless ? 1L : GLFW.glfwGetWindowMonitor(handle);
	}

	@Redirect(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false))
	private void flint$setMonitor(long handle, long monitor, int x, int y, int width, int height, int refreshRate) {
		GLFWVidMode desktop = monitor == 0 ? null : GLFW.glfwGetVideoMode(monitor);
		if (desktop != null && ControlState.settings().gameBool(GameOptions.BORDERLESS_FULLSCREEN)) {
			int[] left = new int[1];
			int[] top = new int[1];
			GLFW.glfwGetMonitorPos(monitor, left, top);
			if (GLFW.glfwGetWindowMonitor(handle) != 0) {
				// From exclusive fullscreen: back to a window first.
				GLFW.glfwSetWindowMonitor(handle, 0, left[0], top[0], desktop.width(), desktop.height(), GLFW.GLFW_DONT_CARE);
			}
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowMonitor(handle, 0, left[0], top[0], desktop.width() + 1, desktop.height(), GLFW.GLFW_DONT_CARE);
			flint$borderless = true;
			return;
		}
		if (flint$borderless) {
			GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			flint$borderless = false;
		}
		GLFW.glfwSetWindowMonitor(handle, monitor, x, y, width, height, refreshRate);
	}
}

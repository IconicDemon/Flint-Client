package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Instant Swap's standby game ({@link InstantSwap}): its window starts windowed even when the player plays fullscreen, so it
 * never takes the screen or a display mode while the player's game runs. GLFW makes Minecraft's window hidden (Minecraft asks
 * for that itself) and Minecraft shows it once it has started; a standby skips that ({@link SwapShowMixin}) and shows itself,
 * in the old window's place and mode, when it takes over. Any other game is untouched.
 */
@Mixin(Window.class)
abstract class SwapWindowMixin {
	@ModifyVariable(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/blaze3d/systems/GpuBackend;)V",
		at = @At("HEAD"), argsOnly = true)
	private static DisplayData flint$windowedWhileStandby(DisplayData data) {
		return InstantSwap.standby() && data.isFullscreen() ? data.withFullscreen(false) : data;
	}
}

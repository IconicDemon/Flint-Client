package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Instant Swap's standby game ({@link InstantSwap}): its window is made hidden (SDL_WINDOW_HIDDEN), and windowed even when the
 * player plays fullscreen, so it never takes the screen or a display mode while the player's game runs. It shows itself, in
 * the old window's place and mode, when it takes over. Any other game is untouched.
 */
@Mixin(Window.class)
abstract class SwapWindowMixin {
	private static final long SDL_WINDOW_HIDDEN = 0x8L;

	@ModifyArg(method = "createWindow", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
	private long flint$hiddenWhileStandby(long flags) {
		return InstantSwap.standby() ? flags | SDL_WINDOW_HIDDEN : flags;
	}

	@ModifyVariable(method = "<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V",
		at = @At("HEAD"), argsOnly = true)
	private static DisplayData flint$windowedWhileStandby(DisplayData data) {
		return InstantSwap.standby() && data.isFullscreen() ? data.withFullscreen(false) : data;
	}
}

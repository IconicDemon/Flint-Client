package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Instant Swap's standby game stays windowed while it waits (SwapWindowMixin): 1.19's start-up turns fullscreen on when the
 * options ask for it, which a standby game doesn't do (its window is hidden; the player's fullscreen comes back when it shows
 * itself, Mc263SwapEngine.show).
 */
@Mixin(Minecraft.class)
abstract class SwapFullscreenMixin {
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;toggleFullScreen()V"))
	private void flint$windowedWhileStandby(Window window) {
		if (!InstantSwap.standby()) {
			window.toggleFullScreen();
		}
	}
}

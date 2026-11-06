package com.flintclient.client.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Mojang loading screen (the game's first resource load, and every resource-pack or language reload after it) gets out
 * of the way as soon as the reload is done. Vanilla holds the logo for 1 s after loading finishes, then cross-fades to the
 * screen for another 1 s (FADE_OUT_TIME, in two steps of 1000 ms), and a pack reload first fades in for 500 ms and waits at
 * least 1 s before it may fade out. Here the two fade-out steps take 75 ms each (the title screen is fully up 150 ms after
 * loading ends) and the fade-in 150 ms with no wait after it. The reload itself is untouched: the overlay still waits for
 * {@code reload.isDone()}, and only the time after that changes.
 *
 * <p>The progress bar stays honest: it still follows the reload's real progress, and once the reload is done it shows full
 * instead of the smoothed value still catching up during the short fade.
 *
 * <p>Every Minecraft line Flint runs on (26.x's extractRenderState, 1.21.x's render through the legacy rules) has these
 * constants; on 1.21.1, which has no isReadyToFadeOut (its wait is two fade-in lengths inside render), that handler simply
 * matches nothing.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayFadeMixin {
	@Shadow
	@Final
	private ReloadInstance reload;

	@Shadow
	private float currentProgress;

	/** Each of the two fade-out steps (logo out, then the screen in under the fading backdrop): 75 ms instead of 1000. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractRenderState", at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "floatValue=1000.0"), require = 0)
	private float flint$fadeOutStep(float vanilla) {
		return 75.0F;
	}

	/** A pack reload's fade-in over the current screen: 150 ms instead of 500. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "extractRenderState", at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "floatValue=500.0"), require = 0)
	private float flint$fadeIn(float vanilla) {
		return 150.0F;
	}

	/** A finished pack reload may fade out as soon as its fade-in is over, not 1 s after it began. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "isReadyToFadeOut", at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "longValue=1000"), require = 0)
	private long flint$readyAfterFadeIn(long vanilla) {
		return 150L;
	}

	/** Once the reload is done the bar shows where it really is (full), not the smoothed value still catching up. */
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void flint$honestBar(CallbackInfo callback) {
		if (reload.isDone()) {
			currentProgress = Math.max(currentProgress, reload.getActualProgress());
		}
	}
}

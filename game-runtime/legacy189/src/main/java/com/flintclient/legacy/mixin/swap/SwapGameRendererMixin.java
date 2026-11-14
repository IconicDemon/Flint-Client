package com.flintclient.legacy.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Instant Swap: a standby game's hidden window is never the active one, and Minecraft opens the pause menu over a world whose
 * window isn't active (Pause on lost focus): while it joins hidden it counts as active, so it arrives in the world unpaused.
 */
@Mixin(GameRenderer.class)
abstract class SwapGameRendererMixin {
	@Redirect(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;isActive()Z", remap = false))
	private boolean flint$activeWhileJoining() {
		return InstantSwap.silent() || Display.isActive();
	}
}

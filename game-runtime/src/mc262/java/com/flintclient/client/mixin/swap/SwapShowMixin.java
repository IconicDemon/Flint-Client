package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Instant Swap's standby game ({@link InstantSwap}) stays hidden: GLFW makes Minecraft's window invisible and Minecraft shows it
 * once its start-up is far enough along; a standby skips that, and shows itself when it takes over (Mc263SwapEngine.show).
 */
@Mixin(Minecraft.class)
abstract class SwapShowMixin {
	@WrapWithCondition(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false))
	private boolean flint$hiddenWhileStandby(long handle) {
		return !InstantSwap.standby();
	}
}

package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After each frame (1.19 presents it and logs its duration in runTick): the 3D cosmetics' per-frame animation budget starts
 * again.
 */
@Mixin(Minecraft.class)
abstract class DebugScreenCosmeticsMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void flint$cosmeticsFrame(boolean tick, CallbackInfo callback) {
		Mc263Cosmetics.frame();
	}
}

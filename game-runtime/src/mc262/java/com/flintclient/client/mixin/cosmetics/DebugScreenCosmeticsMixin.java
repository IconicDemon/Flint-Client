package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After each presented frame (Minecraft logs its duration): the 3D cosmetics' per-frame animation budget starts again. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenCosmeticsMixin {
	@Inject(method = "logFrameDuration", at = @At("HEAD"))
	private void flint$cosmeticsFrame(long duration, CallbackInfo callback) {
		Mc263Cosmetics.frame();
	}
}

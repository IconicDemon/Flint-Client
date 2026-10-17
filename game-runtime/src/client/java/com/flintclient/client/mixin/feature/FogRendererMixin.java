package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fog Customizer: adjusts the fog Minecraft just worked out (distance, colour, clear water and lava). */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@ModifyReturnValue(method = "setupFog", at = @At("RETURN"))
	private FogData flint$fog(FogData fog, Camera camera) {
		Mc263Features.fog(fog, camera.getFluidInCamera());
		return fog;
	}
}

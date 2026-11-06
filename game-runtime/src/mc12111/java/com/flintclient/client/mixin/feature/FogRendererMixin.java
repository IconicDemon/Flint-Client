package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fog Customizer: adjusts the fog Minecraft just worked out (distance, colour, clear water and lava). Minecraft 1.21.11
 * keeps the fog's colour apart from its distances: both are adjusted just before setupFog writes them for the frame.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@Inject(method = "setupFog", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;"))
	private void flint$fog(Camera camera, int renderDistance, DeltaTracker delta, float darken, ClientLevel level, CallbackInfoReturnable<Vector4f> callback,
		@Local FogData fog, @Local Vector4f color) {
		McCompat.fogColor = color;
		Mc263Features.fog(fog, camera.getFluidInCamera());
	}
}

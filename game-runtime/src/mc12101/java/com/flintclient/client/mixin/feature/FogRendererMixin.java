package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.FogData;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fog Customizer on Minecraft 1.21.1: adjusts the fog Minecraft just worked out (distance, colour, clear water and lava).
 * setupFog ends by handing its start and then its end to the shader: the start is held back until the end arrives, then
 * both (and the fog colour setupColor chose this frame) go through the module and are set as it leaves them.
 *
 * <p>1.21.1 sets the fog up twice a frame, for the sky and for the terrain, and draws the clouds with the terrain's: No fog
 * and the distance multiplier (seeing the terrain further) leave the sky's fog and the clouds' alone, as 26.x's own sky and
 * cloud fog ends stay. Without that, the sky lost its horizon fog and the clouds' far edge showed, popping as they moved.
 */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
	@Shadow
	private static float fogRed;
	@Shadow
	private static float fogGreen;
	@Shadow
	private static float fogBlue;
	@Unique
	private static final FogData FLINT$FOG = new FogData();
	@Unique
	private static final FogData FLINT$CLOUDS = new FogData();
	@Unique
	private static float flint$start;

	@WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogStart(F)V"))
	private static void flint$start(float start, Operation<Void> original) {
		flint$start = start;
	}

	@WrapOperation(method = "setupFog", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogEnd(F)V"))
	private static void flint$end(float end, Operation<Void> original, @Local(argsOnly = true) Camera camera,
		@Local(argsOnly = true) FogRenderer.FogMode mode) {
		FogType type = camera.getFluidInCamera();
		// The sky's fog, or a fog set up for the clouds (Sodium's clouds set one up while CloudFogMixin has them drawing).
		boolean sky = mode == FogRenderer.FogMode.FOG_SKY || FogData.drawingClouds;
		McCompat.fogColor.set(fogRed, fogGreen, fogBlue, 1.0F);
		FogData fog = FLINT$FOG.set(flint$start, end);
		Mc263Features.fog(fog, type, sky);
		if (!sky) {
			FogData clouds = FLINT$CLOUDS.set(flint$start, end);
			Mc263Features.fog(clouds, type, true);
			boolean own = clouds.start() != fog.start() || clouds.end() != fog.end();
			FogData.cloudsStart = own ? clouds.start() : Float.NaN;
			FogData.cloudsEnd = own ? clouds.end() : Float.NaN;
		}
		fogRed = McCompat.fogColor.x;
		fogGreen = McCompat.fogColor.y;
		fogBlue = McCompat.fogColor.z;
		RenderSystem.setShaderFogStart(fog.start());
		original.call(fog.end());
	}
}

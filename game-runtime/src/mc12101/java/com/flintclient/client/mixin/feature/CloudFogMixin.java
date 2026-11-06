package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.FogData;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fog Customizer on Minecraft 1.21.1: the clouds are drawn with their own fog (FogData.cloudsStart and cloudsEnd, which
 * FogRendererMixin works out without No fog and the distance multiplier) instead of the terrain's, then the terrain's comes
 * back. 26.x keeps a cloud fog of its own; on 1.21.1, No fog showed the clouds' far edge, popping in and out as they moved.
 * Sodium draws 1.21.1's clouds itself, setting a terrain fog up for them in here: FogData.drawingClouds makes that one a
 * clouds pass too.
 */
@Mixin(LevelRenderer.class)
abstract class CloudFogMixin {
	@WrapMethod(method = "renderClouds")
	private void flint$cloudFog(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, float partialTick, double x, double y, double z,
		Operation<Void> original) {
		float start = FogData.cloudsStart;
		float end = FogData.cloudsEnd;
		boolean swap = !Float.isNaN(start) && !Float.isNaN(end);
		float terrainStart = RenderSystem.getShaderFogStart();
		float terrainEnd = RenderSystem.getShaderFogEnd();
		if (swap) {
			RenderSystem.setShaderFogStart(start);
			RenderSystem.setShaderFogEnd(end);
		}
		FogData.drawingClouds = true;
		try {
			original.call(poseStack, modelView, projection, partialTick, x, y, z);
		} finally {
			FogData.drawingClouds = false;
			if (swap) {
				RenderSystem.setShaderFogStart(terrainStart);
				RenderSystem.setShaderFogEnd(terrainEnd);
			}
		}
	}
}

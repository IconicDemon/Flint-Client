package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.FogData;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fog Customizer on Minecraft 1.19.2: the clouds are drawn with their own fog (FogData.cloudsStart and cloudsEnd, which
 * FogRendererMixin works out without No fog and the distance multiplier) instead of the terrain's, then the terrain's comes
 * back. 26.x keeps a cloud fog of its own; without this, No fog showed the clouds' far edge, popping in and out as they moved.
 * A mod that draws the clouds itself and sets a terrain fog up for them in here (Sodium does on later versions) gets a clouds
 * pass too (FogData.drawingClouds). 1.19.2's renderClouds takes the pose stack and the projection (its own matrix).
 */
@Mixin(LevelRenderer.class)
abstract class CloudFogMixin {
	@WrapMethod(method = "renderClouds")
	private void flint$cloudFog(PoseStack poseStack, Matrix4f projection, float partialTick, double x, double y, double z, Operation<Void> original) {
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
			original.call(poseStack, projection, partialTick, x, y, z);
		} finally {
			FogData.drawingClouds = false;
			if (swap) {
				RenderSystem.setShaderFogStart(terrainStart);
				RenderSystem.setShaderFogEnd(terrainEnd);
			}
		}
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Weather;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Weather Changer's rain and snow colour on Minecraft 1.21.1, which draws them in LevelRenderer.renderSnowAndRain: each
 * corner's white (with its fade) becomes the chosen colour.
 */
@Mixin(LevelRenderer.class)
abstract class WeatherEffectRendererMixin {
	@WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setColor(FFFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer flint$tint(VertexConsumer buffer, float r, float g, float b, float a, Operation<VertexConsumer> original) {
		int argb = Mc263Weather.precipitationColor((int) (a * 255.0F + 0.5F) << 24 | (int) (r * 255.0F + 0.5F) << 16 | (int) (g * 255.0F + 0.5F) << 8
			| (int) (b * 255.0F + 0.5F));
		return original.call(buffer, (argb >> 16 & 0xFF) / 255.0F, (argb >> 8 & 0xFF) / 255.0F, (argb & 0xFF) / 255.0F, a);
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Weather;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Weather Changer's rain and snow color: each column's white (with its fade) becomes the chosen color (the method's name depends on the version). */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererMixin {
	@ModifyExpressionValue(method = com.flintclient.client.compat.McCompat.WEATHER_INSTANCES, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
	private int flint$tint(int color) {
		return Mc263Weather.precipitationColor(color);
	}
}

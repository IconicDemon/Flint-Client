package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lighting: the lightmap's brightness beyond Minecraft's Bright (only how you see; light levels don't change). */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void flint$brightness(LightmapRenderState state, float partialTicks, CallbackInfo callback) {
		state.brightness = Mc263Features.brightness(state.brightness);
	}
}

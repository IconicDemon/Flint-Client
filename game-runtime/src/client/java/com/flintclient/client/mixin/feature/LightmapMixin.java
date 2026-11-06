package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lighting: the lightmap's brightness beyond Minecraft's Bright (only how you see; light levels don't change).
 *
 * <p>Applied where the lightmap is drawn, not where its state is gathered: performance mods such as BadOptimizations
 * cancel the gathering ({@code LightmapRenderStateExtractor.extract}) whenever the sky, the brightness option and the
 * effects look unchanged, which they did when Lighting was turned on, so it never showed (owner's PVP set, 26.2: the
 * picture stayed at 56 of 255 with Lighting on). Here the brightness is set on every draw, and a change of it asks for a
 * redraw, whatever skipped the gathering.
 *
 * <p>Minecraft's own brightness is the option less the Darkness effect (the Warden, sculk shriekers), worked out by the
 * gathering. With Lighting off that value is left exactly as Minecraft made it; with Lighting on, the last one Minecraft
 * made is kept aside and comes back when Lighting goes off. (Writing the bare brightness option instead, as before, undid
 * Darkness's dimming for every player, Lighting on or not.)
 */
@Mixin(Lightmap.class)
abstract class LightmapMixin {
	/** Minecraft's own brightness, as last gathered. */
	@Unique
	private float flint$vanilla = Float.NaN;
	/** What Flint last wrote in its place (NaN: nothing), to tell it from a value Minecraft gathered since. */
	@Unique
	private float flint$written = Float.NaN;

	@Inject(method = "render", at = @At("HEAD"))
	private void flint$brightness(LightmapRenderState state, CallbackInfo callback) {
		float current = state.brightness;
		if (Float.isNaN(flint$written) || Float.compare(current, flint$written) != 0) {
			flint$vanilla = current;
		}
		float wanted = Mc263Features.brightness(flint$vanilla);
		if (Float.compare(current, wanted) != 0) {
			state.brightness = wanted;
			state.needsUpdate = true;
		}
		flint$written = Float.compare(wanted, flint$vanilla) == 0 ? Float.NaN : wanted;
	}
}

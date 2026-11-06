package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lighting: the lightmap's brightness beyond Minecraft's Bright (only how you see; light levels don't change). Minecraft
 * 1.21.11 reads the brightness (the gamma option) where it updates the lightmap.
 *
 * <p>Performance mods such as BadOptimizations cancel that update whenever the sky, the brightness option and the effects
 * look unchanged, which they did when Lighting was turned on, so it never showed (seen on 26.x with the owner's PVP set).
 * So when Lighting's brightness changes, Flint asks for an update and clears such a mod's record of the last inputs (its
 * {@code bo$last…} fields on this lightmap), before it decides: this mixin's priority puts its check ahead of theirs.
 */
@Mixin(value = LightTexture.class, priority = 1500)
abstract class LightmapMixin {
	@Shadow
	private boolean updateLightTexture;

	@Unique
	private float flint$lastBrightness = Float.NaN;

	@Inject(method = "updateLightTexture", at = @At("HEAD"))
	private void flint$redrawWhenChanged(float partialTicks, CallbackInfo callback) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null) {
			return;
		}
		float wanted = Mc263Features.brightness(minecraft.options.gamma().get().floatValue());
		if (Float.compare(wanted, this.flint$lastBrightness) != 0) {
			this.flint$lastBrightness = wanted;
			this.updateLightTexture = true;
			flint$forgetSkippedInputs(this);
		}
	}

	@ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0),
		slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")))
	private float flint$brightness(float gamma) {
		return Mc263Features.brightness(gamma);
	}

	/** Makes another mod's "nothing changed" record differ from the present, so its next check lets the update through. */
	@Unique
	private static void flint$forgetSkippedInputs(Object lightmap) {
		for (Field field : lightmap.getClass().getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || !field.getName().startsWith("bo$last")) {
				continue;
			}
			try {
				field.setAccessible(true);
				Class<?> type = field.getType();
				if (type == float.class) {
					field.setFloat(lightmap, Float.NaN);
				} else if (type == double.class) {
					field.setDouble(lightmap, Double.NaN);
				} else if (type == int.class) {
					field.setInt(lightmap, Integer.MIN_VALUE);
				} else if (type == long.class) {
					field.setLong(lightmap, Long.MIN_VALUE);
				} else if (type == boolean.class) {
					field.setBoolean(lightmap, !field.getBoolean(lightmap));
				} else if (!type.isPrimitive()) {
					field.set(lightmap, null);
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Another mod's private record: if it can't be reset, the next sky or option change updates the lightmap.
			}
		}
	}
}

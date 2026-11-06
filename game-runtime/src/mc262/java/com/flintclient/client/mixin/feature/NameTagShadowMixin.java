package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nametags' shadow on Minecraft 26.2, whose nametag part has no shadow of its own: it's set where the part's text is laid out. */
@Mixin(NameTagFeatureRenderer.class)
abstract class NameTagShadowMixin {
	@WrapOperation(method = "prepareText", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"))
	private static Font.PreparedText flint$shadow(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, boolean includeEmpty,
		int background, Operation<Font.PreparedText> original, @Local(argsOnly = true) NameTagFeatureRenderer.Submit part) {
		return original.call(font, text, x, y, color, shadow || Mc263Nametags.shadow(part.displayMode()), includeEmpty, background);
	}
}

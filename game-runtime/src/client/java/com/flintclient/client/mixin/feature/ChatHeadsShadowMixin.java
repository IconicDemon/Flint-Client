package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.font.PlainTextRenderable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Chat Heads' Render shadow: off, a head glyph is drawn without its shadow, a pixel lower (where Chat Heads puts it). */
@Mixin(PlainTextRenderable.class)
interface ChatHeadsShadowMixin {
	@Shadow
	int shadowColor();

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/font/PlainTextRenderable;shadowColor()I"))
	default int flint$noShadow(int original) {
		return Mc263ChatHeads.shadowColor(original);
	}

	@ModifyArg(method = "render", index = 4, at = @At(value = "INVOKE", target = McCompat.TEXT_SPRITE, ordinal = 1))
	default float flint$shadowlessY(float y) {
		return Mc263ChatHeads.shadowlessY(y, shadowColor());
	}
}

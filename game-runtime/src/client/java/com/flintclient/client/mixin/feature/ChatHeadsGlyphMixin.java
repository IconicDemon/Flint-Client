package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.font.GlyphInfo;
import net.minecraft.client.gui.font.PlayerGlyphProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Chat Heads: the player-head glyph is as wide as a head plus the module's padding while chat is drawn. */
@Mixin(value = PlayerGlyphProvider.class, priority = 500)
abstract class ChatHeadsGlyphMixin {
	@ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/font/GlyphInfo;simple(F)Lcom/mojang/blaze3d/font/GlyphInfo;"))
	private static GlyphInfo flint$padded(GlyphInfo original) {
		return new Mc263ChatHeads.PaddedGlyph(original);
	}
}

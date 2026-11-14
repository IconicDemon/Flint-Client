package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263DarkMode;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Dark Mode on Minecraft 1.19.2: its dark grey menu text (inventory titles and labels) in light grey so it reads on the shaded
 * pictures (GuiGraphicsDarkMixin). 1.19.2 draws GUI text with the font's own PoseStack draws (with and without a shadow),
 * where 1.20 and later draw it through GuiGraphics.drawString; the colour is passed through Mc263DarkMode.label, which
 * changes only that grey and only in Minecraft's menus.
 */
@Mixin(Font.class)
abstract class FontDarkMixin {
	@ModifyVariable(method = {
		"draw(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
		"draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I",
		"draw(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I",
		"drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/String;FFI)I",
		"drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/util/FormattedCharSequence;FFI)I",
		"drawShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/network/chat/Component;FFI)I"}, at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int flint$label(int color) {
		return Mc263DarkMode.label(color);
	}
}

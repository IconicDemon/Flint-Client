package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263DarkMode;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Dark Mode: Minecraft's GUI sprites and textures drawn in its shade (every sprite and texture draw passes through the first
 * two), and its dark grey menu text (inventory titles and labels) in light grey so it reads on them. The sprite and texture
 * draws name the GPU pipeline type, whose package depends on the Minecraft version (McCompat.RENDER_PIPELINE).
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsDarkMixin {
	@ModifyVariable(method = "blitSprite(L" + McCompat.RENDER_PIPELINE + ";Lnet/minecraft/resources/Identifier;IIIII)V",
		at = @At("HEAD"), argsOnly = true, ordinal = 4)
	private int flint$sprite(int color, @Local(argsOnly = true) Identifier sprite) {
		return Mc263DarkMode.sprite(sprite, color);
	}

	@ModifyVariable(method = "blit(L" + McCompat.RENDER_PIPELINE + ";Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
		at = @At("HEAD"), argsOnly = true, ordinal = 8)
	private int flint$texture(int color, @Local(argsOnly = true) Identifier texture) {
		return Mc263DarkMode.texture(texture, color);
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int flint$string(int color) {
		return Mc263DarkMode.label(color);
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), argsOnly = true,
		ordinal = 2)
	private int flint$sequence(int color) {
		return Mc263DarkMode.label(color);
	}

	@ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), argsOnly = true,
		ordinal = 2)
	private int flint$component(int color) {
		return Mc263DarkMode.label(color);
	}
}

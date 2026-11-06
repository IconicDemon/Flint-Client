package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Glint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Glint's tooltips: its flat background in place of Minecraft's sprite for its styles, and the enchantment icons. */
@Mixin(TooltipRenderUtil.class)
abstract class GlintBackgroundMixin {
	@Inject(method = McCompat.TOOLTIP_BACKGROUND, at = @At("HEAD"), cancellable = true)
	private static void flint$glintBackground(GuiGraphicsExtractor graphics, int x, int y, int width, int height, @Nullable Identifier style,
		CallbackInfo callback) {
		if (Mc263Glint.background(graphics, x, y, width, height, style)) {
			callback.cancel();
		}
	}

	@Inject(method = McCompat.TOOLTIP_BACKGROUND, at = @At("RETURN"))
	private static void flint$glintIcons(GuiGraphicsExtractor graphics, int x, int y, int width, int height, @Nullable Identifier style,
		CallbackInfo callback) {
		Mc263Glint.afterBackground(graphics, x, y, style);
	}
}

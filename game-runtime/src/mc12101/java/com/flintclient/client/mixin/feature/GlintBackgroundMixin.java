package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.TooltipStyle;
import com.flintclient.client.control.platform.Mc263Glint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glint's tooltips on Minecraft 1.21.1: its flat background in place of Minecraft's for its styles, and the enchantment
 * icons. The style is the item tooltip's being drawn (compat.TooltipStyle); both are drawn at the depth 1.21.1 hands its
 * background (the tooltip's, above the slots' items).
 */
@Mixin(TooltipRenderUtil.class)
abstract class GlintBackgroundMixin {
	@Inject(method = "renderTooltipBackground", at = @At("HEAD"), cancellable = true)
	private static void flint$glintBackground(GuiGraphics graphics, int x, int y, int width, int height, int z, CallbackInfo callback) {
		// 1.21.1 draws the background at the depth it is given (the tooltip's, over the slots' items), not the pose's.
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, z);
		boolean drawn;
		try {
			drawn = Mc263Glint.background(graphics, x, y, width, height, TooltipStyle.current());
		} finally {
			graphics.pose().popPose();
		}
		if (drawn) {
			callback.cancel();
		}
	}

	@Inject(method = "renderTooltipBackground", at = @At("RETURN"))
	private static void flint$glintIcons(GuiGraphics graphics, int x, int y, int width, int height, int z, CallbackInfo callback) {
		graphics.pose().pushPose();
		graphics.pose().translate(0, 0, z);
		try {
			Mc263Glint.afterBackground(graphics, x, y, TooltipStyle.current());
		} finally {
			graphics.pose().popPose();
		}
	}
}

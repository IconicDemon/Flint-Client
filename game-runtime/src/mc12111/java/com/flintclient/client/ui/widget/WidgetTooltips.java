package com.flintclient.client.ui.widget;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Minecraft 1.21.11's (as 26.1's) widgets have no tooltip hook to override (26.2 added {@code extractTooltipForNextRenderPass}), so
 * {@code mixin.feature.AbstractWidgetTooltipMixin} asks here first: a Flint button draws its own tooltip, in place of
 * vanilla's. In this package so it can call the button's protected method.
 */
public final class WidgetTooltips {
	private WidgetTooltips() {
	}

	/** Whether {@code widget} drew its own tooltip (vanilla's is then left out). */
	public static boolean drawOwn(Object widget, GuiGraphics graphics, int mouseX, int mouseY) {
		if (widget instanceof FlintButton button) {
			button.extractTooltipForNextRenderPass(graphics, mouseX, mouseY);
			return true;
		}
		return false;
	}
}

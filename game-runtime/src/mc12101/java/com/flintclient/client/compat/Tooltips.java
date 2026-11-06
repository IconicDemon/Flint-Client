package com.flintclient.client.compat;

import com.flintclient.client.mixin.bridge.GuiGraphicsInvoker;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;

/** Tooltips from prepared lines on Minecraft 1.21.1 (GuiGraphics keeps that drawing private). */
public final class Tooltips {
	private Tooltips() {
	}

	public static void draw(GuiGraphics graphics, Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner) {
		((GuiGraphicsInvoker) graphics).flint$renderTooltip(font, lines, x, y, positioner);
	}
}

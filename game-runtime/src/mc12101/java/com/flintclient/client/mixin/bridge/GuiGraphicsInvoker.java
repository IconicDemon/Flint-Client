package com.flintclient.client.mixin.bridge;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft 1.21.1: GuiGraphics' own drawing of a tooltip from prepared lines (private there). */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsInvoker {
	@Invoker("renderTooltipInternal")
	void flint$renderTooltip(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner);
}

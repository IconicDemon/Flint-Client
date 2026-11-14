package com.flintclient.client.compat;

import org.joml.Vector2ic;

/** Where a tooltip goes (1.19.3's, which Minecraft 1.19.2 lacks: its tooltips always follow the mouse, {@link DefaultTooltipPositioner}). */
public interface ClientTooltipPositioner {
	Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY, int tooltipWidth, int tooltipHeight);
}

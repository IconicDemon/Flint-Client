package com.flintclient.client.compat;

import org.joml.Vector2i;
import org.joml.Vector2ic;

/** A tooltip up and to the right of the mouse, kept on the screen (as 1.20's, and as 1.19.2 places its own). */
public final class DefaultTooltipPositioner implements ClientTooltipPositioner {
	public static final ClientTooltipPositioner INSTANCE = new DefaultTooltipPositioner();

	private DefaultTooltipPositioner() {
	}

	@Override
	public Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY, int tooltipWidth, int tooltipHeight) {
		Vector2i at = new Vector2i(mouseX, mouseY).add(12, -12);
		if (at.x + tooltipWidth > screenWidth) {
			at.x = Math.max(at.x - 24 - tooltipWidth, 4);
		}
		int height = tooltipHeight + 3;
		if (at.y + height > screenHeight) {
			at.y = screenHeight - height;
		}
		return at;
	}
}

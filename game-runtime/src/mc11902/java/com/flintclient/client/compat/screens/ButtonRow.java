package com.flintclient.client.compat.screens;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * 1.19.3's horizontal {@code LinearLayout} for a row of buttons, on Minecraft 1.19.2, which has no layouts: the widgets side by
 * side in the order added, a gap between each, the row centred on a point (where 1.20's warning screens put their footer).
 */
public final class ButtonRow {
	private final List<AbstractWidget> children = new ArrayList<>();
	private int spacing;

	private ButtonRow() {
	}

	public static ButtonRow horizontal() {
		return new ButtonRow();
	}

	public ButtonRow spacing(int spacing) {
		this.spacing = spacing;
		return this;
	}

	public <T extends AbstractWidget> T addChild(T widget) {
		children.add(widget);
		return widget;
	}

	/** Places the row centred on {@code centreX} with its top at {@code y}, and hands its widgets over (for the screen to add). */
	public List<AbstractWidget> arrange(int centreX, int y) {
		int width = -spacing;
		for (AbstractWidget widget : children) {
			width += widget.getWidth() + spacing;
		}
		int x = centreX - Math.max(0, width) / 2;
		for (AbstractWidget widget : children) {
			widget.x = x;
			widget.y = y;
			x += widget.getWidth() + spacing;
		}
		return children;
	}
}

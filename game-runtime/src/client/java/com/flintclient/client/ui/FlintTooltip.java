package com.flintclient.client.ui;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * Flint's tooltip: a small dark panel with Inter text, drawn above everything after the widgets. The
 * hovered (or keyboard-focused) control requests it during its own drawing; the screen draws the
 * request last. Placed beside controls on the left edge, below everything else, and kept on screen.
 */
public final class FlintTooltip {
	private static @Nullable List<TextLine> pending;
	private static @Nullable ScreenRectangle anchor;

	private FlintTooltip() {
	}

	public static void request(List<TextLine> lines, ScreenRectangle near) {
		pending = lines;
		anchor = near;
	}

	/** Draws the pending tooltip, if any, and clears it. Call at the end of a screen's drawing. */
	public static void draw(GuiGraphicsExtractor graphics, int screenW, int screenH) {
		List<TextLine> lines = pending;
		ScreenRectangle near = anchor;
		pending = null;
		anchor = null;
		if (lines == null || lines.isEmpty() || near == null) {
			return;
		}
		graphics.nextStratum();
		Canvas c = Canvas.begin(graphics);
		try {
			int padX = c.px(3.4F);
			int padY = c.px(2.6F);
			int lineH = c.px(5.4F);
			int textW = 0;
			for (TextLine line : lines) {
				textW = Math.max(textW, c.width(line));
			}
			int w = textW + 2 * padX;
			int h = lines.size() * lineH + 2 * padY - c.px(1.2F);
			int sw = c.px(screenW);
			int sh = c.px(screenH);
			int gap = c.px(2.5F);
			int x;
			int y;
			if (near.left() < screenW * 0.15F) {
				x = c.px(near.right()) + gap;
				y = c.px(near.top() + near.height() / 2.0F) - h / 2;
			} else {
				x = c.px(near.left() + near.width() / 2.0F) - w / 2;
				y = c.px(near.bottom()) + gap;
				if (y + h > sh - gap) {
					y = c.px(near.top()) - gap - h;
				}
			}
			x = Math.clamp(x, gap, Math.max(gap, sw - w - gap));
			y = Math.clamp(y, gap, Math.max(gap, sh - h - gap));
			int border = Math.max(1, Math.round(c.scale() / 4.0F));
			c.panel(x, y, w, h, c.px(2.2F), border, Palette.BORDER_HOVER, 0xF2181A1F);
			int baseline = y + padY + Math.round(lines.getFirst().capHeightPx(c.scale())) + c.px(0.6F);
			for (int i = 0; i < lines.size(); i++) {
				c.text(lines.get(i), x + padX, baseline, i == 0 ? Palette.TEXT : Palette.MUTED);
				baseline += lineH;
			}
		} finally {
			c.end();
		}
	}
}

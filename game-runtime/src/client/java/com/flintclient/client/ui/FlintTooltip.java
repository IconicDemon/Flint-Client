package com.flintclient.client.ui;

import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.TipPlace;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * Flint's tooltip: a small dark panel with Inter text, drawn above everything after the widgets. The
 * hovered (or keyboard-focused) control requests it during its own drawing; the screen draws the
 * request last. Placed beside controls on the left edge (when it fits there), below everything else (above when there is
 * no room below), and kept on screen; a line longer than {@link #MAX_W} or the screen wraps at word boundaries
 * ({@link TipPlace}), worked out again only when the text or the width changes.
 */
public final class FlintTooltip {
	/** At most this wide, in GUI units (a longer line wraps). */
	private static final float MAX_W = 200;
	private static @Nullable List<TextLine> pending;
	private static @Nullable ScreenRectangle anchor;
	private static @Nullable List<TextLine> wrapSource;
	private static int wrapWidth = -1;
	private static int wrapScale = -1;
	private static final List<TextLine> ROWS = new ArrayList<>();
	private static int titleRows;

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
		Canvas c = Canvas.begin(Frame.of(graphics));
		try {
			int padX = c.px(3.4F);
			int padY = c.px(2.6F);
			int lineH = c.px(5.4F);
			int sw = c.px(screenW);
			int sh = c.px(screenH);
			int gap = c.px(2.5F);
			int maxText = TipPlace.textWidth(c.px(MAX_W), sw, gap, padX);
			List<TextLine> rows = wrap(c, lines, maxText);
			int shown = Math.min(rows.size(), TipPlace.rowsThatFit(sh, gap, padY, lineH, c.px(1.2F)));
			int textW = 0;
			for (int i = 0; i < shown; i++) {
				textW = Math.max(textW, c.width(rows.get(i), maxText));
			}
			int w = textW + 2 * padX;
			int h = shown * lineH + 2 * padY - c.px(1.2F);
			int left = c.px(near.left());
			int top = c.px(near.top());
			long at = TipPlace.place(left, top, c.px(near.right()) - left, c.px(near.bottom()) - top, w, h, sw, sh, gap,
				near.left() < screenW * 0.15F);
			int x = TipPlace.x(at);
			int y = TipPlace.y(at);
			int border = Math.max(1, Math.round(c.scale() / 4.0F));
			c.panel(x, y, w, h, c.px(2.2F), border, Palette.BORDER_HOVER, Palette.TOOLTIP);
			int baseline = y + padY + Math.round(rows.getFirst().capHeightPx(c.unitScale())) + c.px(0.6F);
			for (int i = 0; i < shown; i++) {
				c.text(rows.get(i), x + padX, baseline, i < titleRows ? Palette.TEXT : Palette.MUTED, maxText);
				baseline += lineH;
			}
		} finally {
			c.end();
		}
	}

	private static List<TextLine> wrap(Canvas c, List<TextLine> lines, int maxPx) {
		if (lines == wrapSource && maxPx == wrapWidth && c.scale() == wrapScale) {
			return ROWS;
		}
		wrapSource = lines;
		wrapWidth = maxPx;
		wrapScale = c.scale();
		ROWS.clear();
		titleRows = 0;
		for (int i = 0; i < lines.size(); i++) {
			TipPlace.wrap(c, lines.get(i), maxPx, ROWS);
			if (i == 0) {
				titleRows = ROWS.size();
			}
		}
		return ROWS;
	}
}

package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Draws a kit tooltip with the look of {@code FlintTooltip} (dark panel, faint border, a bright title line and
 * muted lines after it) at the kit's interface scale and type scale ({@link Type#META}). A long line wraps at word boundaries
 * to the tooltip's width ({@link #MAX_W}, and never wider than the screen), so a description reads in full at any window
 * size. Beside a control near the left edge (the navigation rail) when it fits there; otherwise below it, above when there
 * is no room below (whichever side has more room when neither fits); always inside the screen ({@link TipPlace}). The
 * wrapped lines are worked out again only when the text, the width or the scale changes.
 */
final class KitTooltip {
	private static final float PAD_X = 5;
	private static final float PAD_Y = 3.5F;
	/** Line pitch for the META lines (5 units, about 1.4 × their size). */
	private static final float LINE = 7;
	private static final float GAP = 3;
	/** At most this wide (a longer line wraps). */
	private static final float MAX_W = 220;

	private TextLine @Nullable [] wrapSource;
	private int wrapWidth = -1;
	private int wrapScale = -1;
	private final List<TextLine> rows = new ArrayList<>();
	/** How many of {@link #rows} the first (title) line became. */
	private int titleRows;

	/**
	 * {@code ax..ah}: the control it belongs to; {@code screenW × screenH}: the screen, all in UI units; {@code k}: how much
	 * larger than the kit's own the tooltip is laid out (its lines' type already at that size).
	 */
	void paint(Canvas c, TextLine[] lines, float ax, float ay, float aw, float ah, float screenW, float screenH, float k, KitScreen.TooltipLook look) {
		int padX = c.px(PAD_X * k);
		int padY = c.px(PAD_Y * k);
		// At least the line's own height as drawn (Minecraft's font, standing in on some screens, is taller than the kit's).
		int lineH = Math.max(c.px(LINE * k), Math.round(lines[0].capHeightPx(c.scale()) * 1.75F));
		int sw = c.px(screenW);
		int sh = c.px(screenH);
		int gap = c.px(GAP * k);
		int maxText = TipPlace.textWidth(c.px(MAX_W * k), sw, gap, padX);
		List<TextLine> wrapped = wrap(c, lines, maxText);
		int shown = Math.min(wrapped.size(), TipPlace.rowsThatFit(sh, gap, padY, lineH, c.px(2 * k)));
		int textW = 0;
		for (int i = 0; i < shown; i++) {
			textW = Math.max(textW, c.width(wrapped.get(i), maxText));
		}
		int w = textW + 2 * padX;
		int h = shown * lineH + 2 * padY - c.px(2 * k);
		boolean beside = ax < screenW * 0.15F && aw < screenW * 0.15F;
		int left = c.px(ax);
		int top = c.px(ay);
		long at = TipPlace.place(left, top, c.px(ax + aw) - left, c.px(ay + ah) - top, w, h, sw, sh, gap, beside);
		int x = TipPlace.x(at);
		int y = TipPlace.y(at);
		c.panel(x, y, w, h, c.px(look.radius() * k), (look.border() >>> 24) == 0 ? 0 : Kit.hairline(c), look.border(), look.fill());
		int baseline = y + padY + Math.round(lines[0].capHeightPx(c.scale())) + c.px(0.7F * k);
		for (int i = 0; i < shown; i++) {
			c.text(wrapped.get(i), x + padX, baseline, i < titleRows ? look.title() : look.text(), maxText);
			baseline += lineH;
		}
	}

	/** {@code lines} wrapped to {@code maxPx}: words kept together on a line while they fit, a word too long on a line of its own. */
	private List<TextLine> wrap(Canvas c, TextLine[] lines, int maxPx) {
		if (lines == wrapSource && maxPx == wrapWidth && c.scale() == wrapScale) {
			return rows;
		}
		wrapSource = lines;
		wrapWidth = maxPx;
		wrapScale = c.scale();
		rows.clear();
		titleRows = 0;
		for (int i = 0; i < lines.length; i++) {
			TipPlace.wrap(c, lines[i], maxPx, rows);
			if (i == 0) {
				titleRows = rows.size();
			}
		}
		return rows;
	}
}

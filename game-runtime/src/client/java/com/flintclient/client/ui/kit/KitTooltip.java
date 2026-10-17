package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;

/**
 * Draws a kit tooltip with the look of {@code FlintTooltip} (dark panel, faint border, a bright title line and
 * muted lines after it) at the kit's interface scale and type scale ({@link Type#META}): beside a control near the left edge (the navigation
 * rail), otherwise below it (above when there is no room), always kept inside the screen.
 */
final class KitTooltip {
	private static final float PAD_X = 5;
	private static final float PAD_Y = 3.5F;
	/** Line pitch for the META lines (5 units, about 1.4 × their size). */
	private static final float LINE = 7;
	private static final float GAP = 3;
	private static final float RADIUS = 4;
	/** At most this wide (a long reason is cut short; its full text is the narration). */
	private static final float MAX_W = 220;

	/** {@code ax..ah}: the control it belongs to; {@code screenW × screenH}: the screen, all in UI units. */
	void paint(Canvas c, TextLine[] lines, float ax, float ay, float aw, float ah, float screenW, float screenH) {
		int padX = c.px(PAD_X);
		int padY = c.px(PAD_Y);
		int lineH = c.px(LINE);
		int maxText = c.px(MAX_W) - 2 * padX;
		int textW = 0;
		for (TextLine line : lines) {
			textW = Math.max(textW, c.width(line, maxText));
		}
		int w = textW + 2 * padX;
		int h = lines.length * lineH + 2 * padY - c.px(2);
		int sw = c.px(screenW);
		int sh = c.px(screenH);
		int gap = c.px(GAP);
		int x;
		int y;
		if (ax < screenW * 0.15F && aw < screenW * 0.15F) {
			x = c.px(ax + aw) + gap;
			y = c.px(ay + ah / 2.0F) - h / 2;
		} else {
			x = c.px(ax + aw / 2.0F) - w / 2;
			y = c.px(ay + ah) + gap;
			if (y + h > sh - gap) {
				y = c.px(ay) - gap - h;
			}
		}
		x = Math.clamp(x, gap, Math.max(gap, sw - w - gap));
		y = Math.clamp(y, gap, Math.max(gap, sh - h - gap));
		c.panel(x, y, w, h, c.px(RADIUS), Kit.hairline(c), Tokens.TOOLTIP_BORDER, Tokens.TOOLTIP_FILL);
		int baseline = y + padY + Math.round(lines[0].capHeightPx(c.scale())) + c.px(0.7F);
		for (int i = 0; i < lines.length; i++) {
			c.text(lines[i], x + padX, baseline, i == 0 ? Tokens2.TEXT : Tokens2.muted(), maxText);
			baseline += lineH;
		}
	}
}

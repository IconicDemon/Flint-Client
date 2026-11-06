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
	/** At most this wide (a long reason is cut short; its full text is the narration). */
	private static final float MAX_W = 220;

	/**
	 * {@code ax..ah}: the control it belongs to; {@code screenW × screenH}: the screen, all in UI units; {@code k}: how much
	 * larger than the kit's own the tooltip is laid out (its lines' type already at that size).
	 */
	void paint(Canvas c, TextLine[] lines, float ax, float ay, float aw, float ah, float screenW, float screenH, float k, KitScreen.TooltipLook look) {
		int padX = c.px(PAD_X * k);
		int padY = c.px(PAD_Y * k);
		// At least the line's own height as drawn (Minecraft's font, standing in on some screens, is taller than the kit's).
		int lineH = Math.max(c.px(LINE * k), Math.round(lines[0].capHeightPx(c.scale()) * 1.75F));
		int maxText = c.px(MAX_W * k) - 2 * padX;
		int textW = 0;
		for (TextLine line : lines) {
			textW = Math.max(textW, c.width(line, maxText));
		}
		int w = textW + 2 * padX;
		int h = lines.length * lineH + 2 * padY - c.px(2 * k);
		int sw = c.px(screenW);
		int sh = c.px(screenH);
		int gap = c.px(GAP * k);
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
		c.panel(x, y, w, h, c.px(look.radius() * k), (look.border() >>> 24) == 0 ? 0 : Kit.hairline(c), look.border(), look.fill());
		int baseline = y + padY + Math.round(lines[0].capHeightPx(c.scale())) + c.px(0.7F * k);
		for (int i = 0; i < lines.length; i++) {
			c.text(lines[i], x + padX, baseline, i == 0 ? look.title() : look.text(), maxText);
			baseline += lineH;
		}
	}
}

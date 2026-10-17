package com.flintclient.client.control.hud;

import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.TextAlign;

/**
 * Where the FPS counter's box and text go, from its settings and the size of its text: the one layout rule the HUD,
 * the HUD editor and the settings preview all use, so they can't drift apart. Plain arithmetic in pixels (no Minecraft
 * types), reused per draw: {@link #layout} writes the fields, nothing is allocated.
 *
 * <ul>
 * <li><b>Just Text</b> and <b>Brackets</b>: no box; the counter is exactly its text.</li>
 * <li><b>Background</b>: a box around the text. Dynamic sizing makes it the text's width plus padding; Static uses the
 * set width, but never narrower than the text with a little padding (so nothing is ever clipped). The height is the set
 * height, never shorter than the text. The text is centred vertically and placed across by the alignment (Dynamic has no
 * spare width, so alignment doesn't matter there).</li>
 * </ul>
 * Sizes in settings are units at 1×; {@code u} is pixels per unit (the GUI scale times the size step).
 */
public final class FpsBox {
	/** Padding across in Dynamic, in units (a Flint panel's). */
	public static final float PAD_X = 4;
	/** The least room kept between the text and a Static box's sides, in units. */
	public static final float MIN_PAD_X = 1.5F;
	/** The least room kept above and below the text, in units. */
	public static final float MIN_PAD_Y = 1;

	/** The counter's size in pixels. */
	public int width;
	public int height;
	/** The text's top-left corner inside the counter, in pixels. */
	public int textX;
	public int textY;
	/** Whether a box is drawn, filled, and how thick its border is (0: none), in pixels. */
	public boolean box;
	public boolean fill;
	public int borderPx;
	public int radiusPx;

	/**
	 * Lays the counter out for text {@code textW × textH} pixels at {@code u} pixels per unit. {@code widthUnits} and
	 * {@code heightUnits} are the Static width and the height; {@code borderHalfUnits} the border's thickness in half units
	 * (a hairline is never under one pixel); {@code radiusUnits} the corner radius.
	 */
	public void layout(int textW, int textH, float u, FpsDisplayMode mode, boolean background, FpsSizing sizing, int widthUnits,
		int heightUnits, boolean border, int borderHalfUnits, int radiusUnits, TextAlign align) {
		textW = Math.max(0, textW);
		textH = Math.max(0, textH);
		if (mode != FpsDisplayMode.BACKGROUND) {
			width = textW;
			height = textH;
			textX = 0;
			textY = 0;
			box = false;
			fill = false;
			borderPx = 0;
			radiusPx = 0;
			return;
		}
		box = true;
		fill = background;
		int minPadX = Math.max(1, Math.round(MIN_PAD_X * u));
		int minPadY = Math.max(1, Math.round(MIN_PAD_Y * u));
		int fitted = textW + 2 * minPadX;
		if (sizing == FpsSizing.DYNAMIC) {
			int padX = Math.max(minPadX, Math.round(PAD_X * u));
			width = textW + 2 * padX;
			textX = padX;
		} else {
			width = Math.max(Math.round(widthUnits * u), fitted);
			int spare = width - textW;
			textX = switch (align) {
				case LEFT -> Math.min(Math.max(minPadX, Math.round(PAD_X * u)), spare / 2);
				case RIGHT -> spare - Math.min(Math.max(minPadX, Math.round(PAD_X * u)), spare / 2);
				case CENTER -> spare / 2;
			};
		}
		height = Math.max(Math.round(heightUnits * u), textH + 2 * minPadY);
		textY = (height - textH) / 2;
		borderPx = border ? Math.max(1, Math.round(borderHalfUnits * u / 2)) : 0;
		radiusPx = Math.min(Math.round(radiusUnits * u), Math.min(width, height) / 2);
	}

	/**
	 * Steadies a Dynamic box's width while the number changes: it grows at once (the text must never be clipped) but only
	 * shrinks once the narrower width has held for {@link #SHRINK_AFTER_NANOS}, so a number flickering between 99 and 100
	 * doesn't make the box twitch. One per surface; allocation-free.
	 */
	public static final class Hold {
		public static final long SHRINK_AFTER_NANOS = 1_000_000_000L;
		private int shown = -1;
		private int pending = -1;
		private long pendingSince;

		/** The width to draw for a wanted width of {@code target} at time {@code nowNanos}. */
		public int width(int target, long nowNanos) {
			if (shown < 0 || target >= shown) {
				shown = target;
				pending = -1;
			} else if (target != pending) {
				pending = target;
				pendingSince = nowNanos;
			} else if (nowNanos - pendingSince >= SHRINK_AFTER_NANOS) {
				shown = target;
				pending = -1;
			}
			return shown;
		}

		/** Forgets the held width (the settings changed, so the next width is taken as it is). */
		public void reset() {
			shown = -1;
			pending = -1;
		}
	}
}

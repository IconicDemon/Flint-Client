package com.flintclient.client.render;

import com.flintclient.client.control.feature.Waypoint;

/**
 * A waypoint's badge, the one mark Flint uses for a waypoint everywhere (the Right Shift list, the minimap, the world map and
 * the label in the world), after the markers of the map mods players know: a small square in the waypoint's colour with its
 * letters in Minecraft's font, its corners softly rounded, a dark edge and a drop shadow under it so it reads on any terrain.
 * A death waypoint is a dark badge with a skull. Letters are white with a dark shadow; on a very light colour (white, light
 * yellow) they turn dark instead. More letters (a name) widen the badge.
 *
 * <p>The plain style is wWaypoints' uncoloured label: a translucent dark box with white letters and a soft black shadow.
 * Drawn in pixels on a {@link Canvas}; nothing is allocated per frame once a text line is cached.
 */
public final class WaypointBadge {
	/** The distance texts under a thousand blocks, made once each. */
	private static final String[] NEAR = new String[1000];
	/** Font Awesome Solid "skull". */
	private static final int SKULL = 0xF54C;
	/** A death badge's fill and the skull's colours (a light skull over a dark shadow). */
	private static final int DEATH_FILL = 0xFF34343A;
	private static final int SKULL_INK = 0xFFFCFCFC;
	private static final int SKULL_SHADOW = 0xFF3E3E3E;

	private WaypointBadge() {
	}

	/**
	 * Draws {@code wp}'s badge {@code box} px tall centred on ({@code cx}, {@code cy}), {@code alpha} opaque. Returns its
	 * width.
	 */
	public static int draw(Canvas c, Waypoint wp, float cx, float cy, int box, float alpha) {
		return draw(c, wp.death(), wp.label(), wp.rgb(), cx, cy, box, alpha);
	}

	/** The coloured badge for any letters and colour ({@code death}: the skull badge, letters and colour unused). */
	public static int draw(Canvas c, boolean death, String letters, int rgb, float cx, float cy, int box, float alpha) {
		if (box < 3 || alpha <= 0.01F) {
			return 0;
		}
		int k = letterScale(box);
		TextLine line = death ? null : Labels.get(letters, PixelFace.fixed(k));
		int w = line == null ? box : Math.max(box, c.width(line) + 2 * pad(box));
		int x = Math.round(cx - w / 2.0F);
		int y = Math.round(cy - box / 2.0F);
		int r = Math.max(1, Math.round(box * 0.24F));
		int edge = Math.max(1, box / 14);
		int drop = Math.max(1, box / 10);
		// Shadow, dark edge, then the fill with a soft light along its top.
		c.roundRectCorners(x + drop - edge, y + drop - edge, w + 2 * edge, box + 2 * edge, r + edge, Palette.withAlpha(0x55000000, alpha));
		c.roundRectCorners(x - edge, y - edge, w + 2 * edge, box + 2 * edge, r + edge, Palette.withAlpha(0xE6000000, alpha));
		int fill = death ? DEATH_FILL : 0xFF000000 | rgb;
		c.roundGradient(x, y, w, box, r, Palette.withAlpha(Palette.lerp(fill, 0xFFFFFFFF, 0.22F), alpha),
			Palette.withAlpha(Palette.lerp(fill, 0xFF000000, 0.08F), alpha));
		if (death) {
			skull(c, cx, cy, box, alpha);
			return w;
		}
		boolean light = luminance(rgb) > 190;
		letters(c, line, cx, cy, k, light ? 0xFF16161A : 0xFFFFFFFF, light ? 0 : 0x8C, alpha);
		return w;
	}

	/**
	 * wWaypoints' plain label: a box {@code box} px tall of {@code backgroundArgb} (its alpha is the Label background), the
	 * text white with a black shadow of {@code shadowAlpha} (0..255, 0: none) one font pixel down and right. Returns its width.
	 */
	public static int drawPlain(Canvas c, boolean death, String text, float cx, float cy, int box, int backgroundArgb, int shadowAlpha,
		float alpha) {
		if (box < 3 || alpha <= 0.01F) {
			return 0;
		}
		int k = letterScale(box);
		TextLine line = death ? null : Labels.get(text, PixelFace.fixed(k));
		int w = line == null ? box : Math.max(box, c.width(line) + 2 * pad(box));
		int x = Math.round(cx - w / 2.0F);
		int y = Math.round(cy - box / 2.0F);
		if ((backgroundArgb >>> 24) > 0) {
			c.roundRectCorners(x, y, w, box, Math.max(1, Math.round(box * 0.12F)), Palette.withAlpha(backgroundArgb, alpha));
		}
		if (death) {
			skull(c, cx, cy, box, alpha);
		} else {
			letters(c, line, cx, cy, k, 0xFFFFFFFF, shadowAlpha, alpha);
		}
		return w;
	}

	/** The width {@link #draw} or {@link #drawPlain} gives {@code text} in a {@code box} px badge. */
	public static int width(Canvas c, String text, int box) {
		return Math.max(box, c.width(Labels.get(text, PixelFace.fixed(letterScale(box)))) + 2 * pad(box));
	}

	private static void letters(Canvas c, TextLine line, float cx, float cy, int k, int ink, int shadowAlpha, float alpha) {
		int baseline = c.baselineFor(line, Math.round(cy));
		int tx = Math.round(cx - c.width(line) / 2.0F);
		if (shadowAlpha > 0) {
			c.text(line, tx + k, baseline + k, Palette.withAlpha(shadowAlpha << 24, alpha));
		}
		c.text(line, tx, baseline, Palette.withAlpha(ink, alpha));
	}

	private static void skull(Canvas c, float cx, float cy, int box, float alpha) {
		int g = Math.max(3, Math.round(box * 0.72F));
		int gx = Math.round(cx - g / 2.0F);
		int gy = Math.round(cy - g / 2.0F);
		int off = Math.max(1, g / 10);
		c.glyph(SKULL, gx + off, gy + off, g, Palette.withAlpha(SKULL_SHADOW, alpha));
		c.glyph(SKULL, gx, gy, g, Palette.withAlpha(SKULL_INK, alpha));
	}

	/**
	 * Minecraft's font at a whole pixel scale for a {@code box} px badge: 1 up to 15 px, 2 from 16, 3 from 25… (a 9 px badge per
	 * scale step, Xaero's 7-pixel capitals in a 9-pixel square).
	 */
	public static int letterScale(int box) {
		return Math.max(1, (box + 2) / 9);
	}

	private static int pad(int box) {
		return Math.max(2, box / 5);
	}

	static int luminance(int rgb) {
		return ((rgb >> 16 & 255) * 299 + (rgb >> 8 & 255) * 587 + (rgb & 255) * 114) / 1000;
	}

	/** wWaypoints' distance text: "[37m]", "[1.2K]" from a thousand, "[12K]" from ten thousand, "[1.2M]" from a million. */
	public static String distanceText(double blocks) {
		long m = Math.max(0, Math.round(blocks));
		if (m < 1000) {
			String s = NEAR[(int) m];
			if (s == null) {
				s = "[" + m + "m]";
				NEAR[(int) m] = s;
			}
			return s;
		}
		if (m < 10_000) {
			long tenths = (m + 50) / 100;
			return tenths >= 100 ? "[10K]" : "[" + tenths / 10 + "." + tenths % 10 + "K]";
		}
		if (m < 1_000_000) {
			long k = (m + 500) / 1000;
			return k >= 1000 ? "[1.0M]" : "[" + k + "K]";
		}
		long tenths = (m + 50_000) / 100_000;
		return tenths >= 100 ? "[" + tenths / 10 + "M]" : "[" + tenths / 10 + "." + tenths % 10 + "M]";
	}
}

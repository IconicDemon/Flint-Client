package com.flintclient.client.render;

/**
 * Flint's one notification style, the same on every line (26.x, 1.21.x, 1.19.x, 1.8.9): a slim, borderless, softly translucent
 * pill with Flint's small mark, a thin accent in the player's theme, white text and grey secondary text in Minecraft's font. It eases in, fades
 * out, and always fits the screen: never wider than the screen less a margin either side (a long name is cut short with an
 * ellipsis), whatever the window size and GUI scale. Platform-free: the toasts (26.x to 1.19.x) and 1.8.9's notice draw with it.
 */
public final class NoticeStyle {
	/** The pill's height and the space kept free at each screen edge, in GUI units. */
	public static final float HEIGHT = 16;
	public static final float MARGIN = 4;
	/** Inner padding, the gap after the mark and the accent's width, in GUI units. */
	public static final float PAD = 7;
	public static final float GAP = 5;
	/** How long a notice eases in and fades out. */
	public static final long FADE_IN_MILLIS = 220;
	public static final long FADE_OUT_MILLIS = 420;
	/** Text and secondary text: white and grey on the dark pill; cocoa and its muted tone on a light theme's cream one. */
	public static int TEXT = 0xFFFFFFFF;
	public static int SECONDARY = 0xFFB4B4B4;
	/** The accent mark and key chips: the theme's accent tint (follows {@link Palette}). */
	public static int ACCENT = Palette.ACCENT_TEXT;
	/** The pill: near-black, about half see-through (cream at 92% on a light theme). */
	public static int FILL = 0x9E0C0C0E;

	static {
		Palette.follow(NoticeStyle::followTheme);
	}

	private NoticeStyle() {
	}

	private static void followTheme() {
		ACCENT = Palette.ACCENT_TEXT;
		boolean light = Palette.light();
		TEXT = light ? 0xFF2A211C : 0xFFFFFFFF;
		SECONDARY = light ? 0xFF6E5E52 : 0xFFB4B4B4;
		FILL = light ? 0xEBFBF6EE : 0x9E0C0C0E;
	}

	/**
	 * The width a notice takes: what its content wants ({@code wantedPx}), never more than the screen ({@code screenPx}) less
	 * {@code marginPx} at each side, never below zero.
	 */
	public static int fitWidth(int wantedPx, int screenPx, int marginPx) {
		return Math.max(0, Math.min(wantedPx, screenPx - 2 * marginPx));
	}

	/**
	 * How opaque a notice is {@code visibleMillis} after it appeared, shown for {@code showMillis}: easing in, then a gentle fade
	 * at its end (0 to 1).
	 */
	public static float alpha(long visibleMillis, long showMillis) {
		float in = visibleMillis >= FADE_IN_MILLIS ? 1.0F : easeOut(Math.max(0, visibleMillis) / (float) FADE_IN_MILLIS);
		long left = showMillis - visibleMillis;
		float out = left >= FADE_OUT_MILLIS ? 1.0F : Math.max(0.0F, left / (float) FADE_OUT_MILLIS);
		return Math.min(in, out * out * (3 - 2 * out));
	}

	/** {@code argb} with its alpha multiplied by {@code alpha} (0 to 1). */
	public static int fade(int argb, float alpha) {
		int a = Math.round((argb >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha)));
		return a << 24 | argb & 0xFFFFFF;
	}

	/**
	 * Draws the pill at ({@code x}, {@code y}), {@code w} by {@code h} pixels: the translucent body, the orange accent and Flint's
	 * mark at its left. Returns where the text starts.
	 */
	public static int pill(Canvas c, int x, int y, int w, int h, float alpha) {
		c.roundRect(x, y, w, h, h / 2, fade(FILL, alpha));
		int accentW = Math.max(1, c.px(1));
		int accentH = Math.max(2, Math.round(h * 0.5F));
		int ax = x + Math.max(c.px(4), h / 3);
		c.roundRect(ax, y + (h - accentH) / 2, accentW, accentH, accentW / 2, fade(ACCENT, alpha));
		int markH = Math.max(2, Math.round(h * 0.56F));
		int mx = ax + accentW + c.px(3);
		c.mark(mx, y + (h - markH) / 2, markH, alpha);
		return mx + MaskAtlas.markWidth(markH) + c.px(GAP);
	}

	/** The pixels a pill takes before its text (padding, accent, mark, gap) and after it, for {@code h}-pixel tall pill. */
	public static int chromeWidth(Canvas c, int h) {
		int accentW = Math.max(1, c.px(1));
		int markH = Math.max(2, Math.round(h * 0.56F));
		return Math.max(c.px(4), h / 3) + accentW + c.px(3) + MaskAtlas.markWidth(markH) + c.px(GAP) + c.px(PAD);
	}

	/** A key on a small chip outlined in orange ("RIGHT SHIFT"), its text at {@code baseline}; returns the chip's width. */
	public static int keyChip(Canvas c, TextLine key, int x, int centreY, int heightPx, float alpha) {
		int pad = c.px(3);
		int w = c.width(key) + 2 * pad;
		c.panel(x, centreY - heightPx / 2, w, heightPx, Math.max(1, c.px(2)), Math.max(1, c.px(0.5F)), fade(ACCENT, alpha), fade(0x33000000, alpha));
		c.text(key, x + pad, c.baselineFor(key, centreY), fade(ACCENT, alpha));
		return w;
	}

	private static float easeOut(float t) {
		float u = 1 - t;
		return 1 - u * u * u;
	}
}

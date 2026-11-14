package com.flintclient.client.render;

import org.jspecify.annotations.Nullable;

/**
 * Another mod's icon as a white mark for Flint's main menu, so it sits with Flint's own white icons rather than in the mod's
 * colours (Mod Menu's blue square). An icon drawn on a solid tile of its own (Mod Menu's: a white list on blue) keeps what
 * stands out from that tile, in white, and loses the tile; an icon on a clear background keeps its shape, its light parts
 * white and its dark parts grey, so its details still read. Small pixel-art icons are enlarged by whole pixels, so they stay
 * sharp when drawn. Null when no clean mark comes of it (the menu then shows the mod's initial, white): nothing left to see,
 * a solid block, or a picture from edge to edge (Mod Menu 17's hand-drawn "MOD MENU" on blue), whose detail turns to noise at
 * the size of a button. Pure Java.
 */
public final class WhiteMark {
	/** At least this many pixels across (by whole multiples, pixel for pixel), so a 16-pixel icon stays crisp. */
	static final int MIN_SIZE = 64;
	/** How far (0 to 441, across red, green and blue) a pixel's colour must be from the tile's to start and to finish showing. */
	private static final float NEAR = 30.0F;
	private static final float FAR = 110.0F;

	private WhiteMark() {
	}

	/** {@code icon} as a white mark (white and greys, the shape in the alpha), or null when no clean mark comes of it. */
	public static @Nullable Pixels of(Pixels icon) {
		int w = icon.width();
		int h = icon.height();
		if (w <= 0 || h <= 0 || icon.argb().length < w * h) {
			return null;
		}
		int[] src = icon.argb();
		int tile = tileColour(src, w, h);
		if (tile == 0 && solidShare(src, w * h) >= 0.9F) {
			// A picture from edge to edge: no tile to take away, and too much detail for a button.
			return null;
		}
		int[] out = new int[w * h];
		boolean onTile = tile != 0;
		int tr = (tile >> 16) & 0xFF;
		int tg = (tile >> 8) & 0xFF;
		int tb = tile & 0xFF;
		int seen = 0;
		for (int i = 0; i < w * h; i++) {
			int p = src[i];
			int a = p >>> 24;
			int r = (p >> 16) & 0xFF;
			int g = (p >> 8) & 0xFF;
			int b = p & 0xFF;
			int alpha;
			int grey;
			if (onTile) {
				// What differs from the tile shows, white; the tile itself goes.
				float d = (float) Math.sqrt((r - tr) * (r - tr) + (g - tg) * (g - tg) + (b - tb) * (b - tb));
				alpha = Math.round(a * smooth((d - NEAR) / (FAR - NEAR)));
				grey = 255;
			} else {
				// Its own shape; light parts white, dark parts a mid grey (outlines and details still show).
				alpha = a;
				float light = (0.2126F * r + 0.7152F * g + 0.0722F * b) / 255.0F;
				grey = Math.round(255 * (0.55F + 0.45F * light));
			}
			if (alpha >= 90) {
				seen++;
			}
			out[i] = alpha << 24 | grey << 16 | grey << 8 | grey;
		}
		// Nothing left (a tile of one colour), or a solid block that reads as no icon at all.
		if (seen < Math.max(2, w * h / 40) || seen > w * h * 9 / 10) {
			return null;
		}
		return enlarged(new Pixels(w, h, out));
	}

	/**
	 * The colour of the tile an icon is drawn on: its edge, when nearly all of the edge is solid and of one colour (corners
	 * cut round don't count against it); 0 when the icon stands on a clear background or its edge is a picture of its own.
	 */
	static int tileColour(int[] argb, int w, int h) {
		if (w < 4 || h < 4) {
			return 0;
		}
		long r = 0;
		long g = 0;
		long b = 0;
		int solid = 0;
		int ring = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (x != 0 && y != 0 && x != w - 1 && y != h - 1) {
					continue;
				}
				ring++;
				int p = argb[y * w + x];
				if (p >>> 24 >= 200) {
					solid++;
					r += (p >> 16) & 0xFF;
					g += (p >> 8) & 0xFF;
					b += p & 0xFF;
				}
			}
		}
		if (solid < ring * 3 / 4) {
			return 0;
		}
		int mr = (int) (r / solid);
		int mg = (int) (g / solid);
		int mb = (int) (b / solid);
		// One colour: the edge's solid pixels sit close to their average.
		double spread = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (x != 0 && y != 0 && x != w - 1 && y != h - 1) {
					continue;
				}
				int p = argb[y * w + x];
				if (p >>> 24 >= 200) {
					int dr = ((p >> 16) & 0xFF) - mr;
					int dg = ((p >> 8) & 0xFF) - mg;
					int db = (p & 0xFF) - mb;
					spread += Math.sqrt(dr * dr + dg * dg + db * db);
				}
			}
		}
		return spread / solid <= 24 ? 0xFF000000 | mr << 16 | mg << 8 | mb : 0;
	}

	/** The share of the pixels that are solid. */
	static float solidShare(int[] argb, int n) {
		int solid = 0;
		for (int i = 0; i < n; i++) {
			if (argb[i] >>> 24 >= 200) {
				solid++;
			}
		}
		return n == 0 ? 0 : solid / (float) n;
	}

	/** {@code image} enlarged by the least whole factor that makes it {@link #MIN_SIZE} across, pixel for pixel. */
	static Pixels enlarged(Pixels image) {
		int largest = Math.max(image.width(), image.height());
		int k = largest >= MIN_SIZE ? 1 : (MIN_SIZE + largest - 1) / largest;
		if (k <= 1) {
			return image;
		}
		int w = image.width() * k;
		int h = image.height() * k;
		int[] out = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				out[y * w + x] = image.argb()[(y / k) * image.width() + x / k];
			}
		}
		return new Pixels(w, h, out);
	}

	private static float smooth(float t) {
		float u = Math.clamp(t, 0.0F, 1.0F);
		return u * u * (3 - 2 * u);
	}
}

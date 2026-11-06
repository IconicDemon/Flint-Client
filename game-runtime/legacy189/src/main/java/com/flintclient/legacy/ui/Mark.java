package com.flintclient.legacy.ui;

/**
 * The Flint mark (the brand SVG's three facets on its 512-unit canvas, cropped to their bounds), rasterised at the pixel
 * height it is shown at with its own colours, 4 × 4 samples a pixel. Render thread.
 */
public final class Mark {
	private static final float LEFT = 161;
	private static final float TOP = 60;
	private static final float HEIGHT = 350;
	public static final float ASPECT = 190.0F / 350.0F;
	/** Each facet: x coordinates, y coordinates, colour. Painted in order, each over the ones before. */
	private static final float[][] XS = {{256, 351, 256, 161}, {256, 351, 256}, {239, 318, 239, 194}};
	private static final float[][] YS = {{60, 228, 312, 228}, {60, 228, 186}, {172, 284, 410, 284}};
	private static final int[] COLORS = {0xB45309, 0xD97706, 0xFEF3E2};

	private Mark() {
	}

	public static int width(int heightPx) {
		return Math.round(heightPx * ASPECT);
	}

	/** Draws the mark {@code heightPx} tall with its top left at ({@code x}, {@code y}). */
	public static void draw(float x, float y, int heightPx, float alpha) {
		long key = Atlas.key(Atlas.KIND_MARK, heightPx, 0, 0);
		Atlas.Entry e = Atlas.get().find(key);
		if (e == null) {
			int w = width(heightPx);
			e = Atlas.get().add(key, w, heightPx, pixels(w, heightPx));
		}
		Gfx.entry(e, x, y, Gfx.withAlpha(0xFFFFFF, alpha));
	}

	private static int[] pixels(int width, int height) {
		float unit = height / HEIGHT;
		int[] out = new int[width * height];
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				float r = 0;
				float g = 0;
				float b = 0;
				float a = 0;
				for (int f = 0; f < XS.length; f++) {
					int inside = 0;
					for (int sy = 0; sy < 4; sy++) {
						for (int sx = 0; sx < 4; sx++) {
							float lx = LEFT + (px + (sx + 0.5F) / 4) / unit;
							float ly = TOP + (py + (sy + 0.5F) / 4) / unit;
							if (contains(XS[f], YS[f], lx, ly)) {
								inside++;
							}
						}
					}
					if (inside == 0) {
						continue;
					}
					float c = inside / 16.0F;
					int rgb = COLORS[f];
					r = ((rgb >> 16) & 255) / 255.0F * c + r * (1 - c);
					g = ((rgb >> 8) & 255) / 255.0F * c + g * (1 - c);
					b = (rgb & 255) / 255.0F * c + b * (1 - c);
					a = c + a * (1 - c);
				}
				if (a > 0) {
					out[py * width + px] = (Math.round(a * 255) << 24) | (Math.round(r / a * 255) << 16) | (Math.round(g / a * 255) << 8)
						| Math.round(b / a * 255);
				}
			}
		}
		return out;
	}

	/** Even-odd point in polygon. */
	private static boolean contains(float[] xs, float[] ys, float x, float y) {
		boolean in = false;
		for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
			if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
				in = !in;
			}
		}
		return in;
	}
}

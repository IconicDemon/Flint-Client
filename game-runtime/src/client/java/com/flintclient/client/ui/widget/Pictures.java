package com.flintclient.client.ui.widget;

import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;

/**
 * Pictures on the main menu's cards: a texture covering a pixel box (its middle at the box's shape, as CSS's {@code cover}),
 * with rounded corners even over the menu's artwork (the corner rows are drawn narrower, one row at a time, so nothing has
 * to be painted over them; the card's antialiased ring on top smooths the edge).
 */
final class Pictures {
	private Pictures() {
	}

	/**
	 * {@code texture} ({@code texW × texH}) covering the box ({@code x}, {@code y}, {@code w}, {@code h}) with corners of
	 * {@code radius} px, enlarged by {@code zoom} about its middle, tinted {@code argb}.
	 */
	static void cover(Canvas c, Texture texture, int texW, int texH, int x, int y, int w, int h, int radius, float zoom, int argb) {
		if (w <= 0 || h <= 0 || texW <= 0 || texH <= 0) {
			return;
		}
		float box = w / (float) h;
		float sw = texW;
		float sh = texH;
		if (texW / (float) texH > box) {
			sw = texH * box;
		} else {
			sh = texW / box;
		}
		sw /= zoom;
		sh /= zoom;
		float u0 = (texW - sw) / 2;
		float v0 = (texH - sh) / 2;
		int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
		float perRow = sh / h;
		float perCol = sw / w;
		// The middle in one draw.
		int midTop = y + r;
		int midH = h - 2 * r;
		if (midH > 0) {
			c.textureRegion(texture, x, midTop, w, midH, Math.round(u0), Math.round(v0 + r * perRow), Math.round(sw), Math.max(1, Math.round(midH * perRow)),
				texW, texH, argb);
		}
		// The corner rows, each as wide as the rounded edge allows there.
		for (int i = 0; i < r; i++) {
			float dy = r - i - 0.5F;
			int inset = Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
			int rowW = w - 2 * inset;
			if (rowW <= 0) {
				continue;
			}
			int srcU = Math.round(u0 + inset * perCol);
			int srcW = Math.max(1, Math.round(rowW * perCol));
			int srcH = Math.max(1, Math.round(perRow));
			c.textureRegion(texture, x + inset, y + i, rowW, 1, srcU, Math.round(v0 + i * perRow), srcW, srcH, texW, texH, argb);
			c.textureRegion(texture, x + inset, y + h - 1 - i, rowW, 1, srcU, Math.round(v0 + (h - 1 - i) * perRow), srcW, srcH, texW, texH, argb);
		}
	}
}

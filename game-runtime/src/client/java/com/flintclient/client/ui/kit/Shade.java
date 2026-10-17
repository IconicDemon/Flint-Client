package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;

/**
 * Horizontal gradients drawn as stepped vertical strips (Minecraft's own gradient fill is vertical only): the
 * world dim beside the deck, the deck's soft edge shadow and the motion smear of a moving edge. Strip edges are
 * whole pixels, so neighbours never overlap or leave a gap, and enough strips are used that no step shows.
 * Allocation-free. Render thread.
 */
public final class Shade {
	private Shade() {
	}

	/**
	 * Fills {@code x0..x1 × y0..y1} (pixels) with {@code rgb} whose alpha runs from {@code alphaAtX0} at the left
	 * edge to {@code alphaAtX1} at the right, in {@code strips} steps (each strip takes its centre's value).
	 */
	public static void horizontal(Canvas c, int x0, int y0, int x1, int y1, int rgb, float alphaAtX0, float alphaAtX1, int strips) {
		int width = x1 - x0;
		if (width <= 0 || y1 <= y0) {
			return;
		}
		int n = Math.max(1, Math.min(strips, width));
		int base = rgb | 0xFF000000;
		for (int i = 0; i < n; i++) {
			int from = x0 + (int) ((long) width * i / n);
			int to = x0 + (int) ((long) width * (i + 1) / n);
			float t = (i + 0.5F) / n;
			float alpha = alphaAtX0 + (alphaAtX1 - alphaAtX0) * t;
			if (alpha > 0.002F && to > from) {
				c.fill(from, y0, to, y1, Palette.withAlpha(base, alpha));
			}
		}
	}

	/**
	 * A soft shadow or smear: {@code rgb} at {@code alpha} at its near edge falling to nothing at the far edge
	 * along a squared curve (a soft shoulder, as a real penumbra fades). {@code nearAtX0}: the near edge is the
	 * left one ({@code x0}), else the right one.
	 */
	public static void falloff(Canvas c, int x0, int y0, int x1, int y1, int rgb, float alpha, boolean nearAtX0, int strips) {
		int width = x1 - x0;
		if (width <= 0 || y1 <= y0 || alpha <= 0.002F) {
			return;
		}
		int n = Math.max(1, Math.min(strips, width));
		int base = rgb | 0xFF000000;
		for (int i = 0; i < n; i++) {
			int from = x0 + (int) ((long) width * i / n);
			int to = x0 + (int) ((long) width * (i + 1) / n);
			float t = (i + 0.5F) / n;
			float d = nearAtX0 ? t : 1.0F - t;
			float a = alpha * (1.0F - d) * (1.0F - d);
			if (a > 0.002F && to > from) {
				c.fill(from, y0, to, y1, Palette.withAlpha(base, a));
			}
		}
	}

	/**
	 * A strip count for a gradient {@code widthPx} wide whose alpha changes by {@code alphaRange}: enough that one
	 * step changes the alpha by under half a percent, and at most one strip per pixel or 48 strips.
	 */
	public static int strips(int widthPx, float alphaRange) {
		int byAlpha = (int) Math.ceil(Math.abs(alphaRange) / 0.005F);
		return Math.clamp(byAlpha, 1, Math.max(1, Math.min(48, widthPx)));
	}
}

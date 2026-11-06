package com.flintclient.client.render;

import com.flintclient.client.FlintLog;
import com.flintclient.client.platform.Images;
import com.flintclient.client.platform.Texture;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The Merit, Flint's earned currency, as its 23 × 23 pixel-art star ({@code textures/gui/merit.png}). Drawn crisp at whole
 * multiples of its pixels when the size asked for is near one (within {@link #SNAP}); at any other size the picture is
 * averaged down (or up) to exactly that many pixels, made once per size, so it never shimmers or loses a pixel row.
 * Render thread; a missing picture draws nothing.
 */
public final class MeritIcon {
	public static final String PATH = "textures/gui/merit.png";
	/** How far from a whole multiple of the art a size may be and still be drawn crisp at that multiple. */
	private static final float SNAP = 0.18F;
	private static final int MAX_SIZES = 12;

	private static @Nullable Pixels art;
	private static boolean read;
	private static @Nullable Texture crisp;
	private static final Map<Integer, Texture> SIZED = new HashMap<>();

	private MeritIcon() {
	}

	/** The star filling a {@code sizePx} square centred on ({@code centerX}, {@code centerY}), tinted by {@code argb}. */
	public static void draw(Canvas c, int centerX, int centerY, int sizePx, int argb) {
		Pixels pixels = art();
		if (pixels == null || sizePx <= 0) {
			return;
		}
		int n = pixels.width();
		int whole = Math.max(1, Math.round(sizePx / (float) n));
		if (Math.abs(sizePx - whole * n) <= SNAP * sizePx) {
			if (crisp == null) {
				crisp = Texture.upload("merit/crisp", n, pixels.height(), pixels.argb(), false);
			}
			int s = whole * n;
			c.texture(crisp, centerX - s / 2, centerY - s / 2, s, s, n, n, argb);
			return;
		}
		Texture sized = SIZED.get(sizePx);
		if (sized == null) {
			if (SIZED.size() >= MAX_SIZES) {
				SIZED.values().forEach(Texture::release);
				SIZED.clear();
			}
			sized = Texture.upload("merit/" + sizePx, sizePx, sizePx, resample(pixels, sizePx), false);
			SIZED.put(sizePx, sized);
		}
		c.texture(sized, centerX - sizePx / 2, centerY - sizePx / 2, sizePx, sizePx, sizePx, sizePx, argb);
	}

	/**
	 * The star at any size from its own pixels (nearest), for sizes that change every frame (Merits flying, a pulse): no
	 * picture is made per size.
	 */
	public static void drawMoving(Canvas c, int centerX, int centerY, int sizePx, int argb) {
		Pixels pixels = art();
		if (pixels == null || sizePx <= 0) {
			return;
		}
		int n = pixels.width();
		if (crisp == null) {
			crisp = Texture.upload("merit/crisp", n, pixels.height(), pixels.argb(), false);
		}
		c.texture(crisp, centerX - sizePx / 2, centerY - sizePx / 2, sizePx, sizePx, n, n, argb);
	}

	/** The size nearest {@code sizePx} at which the star is drawn crisp: a whole multiple of its pixels from 40 up. */
	public static int crispSize(int sizePx) {
		int n = 23;
		return sizePx >= 40 ? Math.max(1, Math.round(sizePx / (float) n)) * n : sizePx;
	}

	private static @Nullable Pixels art() {
		if (!read) {
			read = true;
			try {
				art = Images.read(PATH);
			} catch (java.io.IOException | RuntimeException e) {
				FlintLog.LOGGER.warn("Flint: couldn't read the Merit picture", e);
			}
		}
		return art;
	}

	/** {@code src} averaged by area into {@code size × size} pixels (colour weighted by alpha, so edges don't darken). */
	static int[] resample(Pixels src, int size) {
		int n = src.width();
		int[] out = new int[size * size];
		double scale = n / (double) size;
		for (int oy = 0; oy < size; oy++) {
			double y0 = oy * scale;
			double y1 = y0 + scale;
			for (int ox = 0; ox < size; ox++) {
				double x0 = ox * scale;
				double x1 = x0 + scale;
				double a = 0;
				double r = 0;
				double g = 0;
				double b = 0;
				double area = 0;
				for (int sy = (int) y0; sy < Math.min(n, Math.ceil(y1)); sy++) {
					double hy = Math.min(y1, sy + 1) - Math.max(y0, sy);
					for (int sx = (int) x0; sx < Math.min(n, Math.ceil(x1)); sx++) {
						double wgt = hy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
						int p = src.get(sx, sy);
						double pa = (p >>> 24) / 255.0 * wgt;
						a += pa;
						r += (p >> 16 & 0xFF) * pa;
						g += (p >> 8 & 0xFF) * pa;
						b += (p & 0xFF) * pa;
						area += wgt;
					}
				}
				if (a <= 0 || area <= 0) {
					continue;
				}
				int alpha = (int) Math.round(Math.min(1, a / area) * 255);
				out[oy * size + ox] = alpha << 24 | (int) Math.round(r / a) << 16 | (int) Math.round(g / a) << 8 | (int) Math.round(b / a);
			}
		}
		return out;
	}
}

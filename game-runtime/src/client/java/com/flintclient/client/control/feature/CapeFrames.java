package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.CapeAnimation;
import java.nio.ByteOrder;

/**
 * An animated cape's frames as the game keeps them: the cape region (22×17 atlas units) of each frame, at {@link #keptScale}
 * pixels per unit, one {@code int[]} per frame in the layout the engine writes ({@link Layout}), so showing a frame is one copy
 * and one upload of that rectangle.
 *
 * <ul>
 * <li>Scale: the animation's, at most {@link #MAX_SCALE} (16 px per unit, the poster's own cap and the Store's viewers'), then
 * halved while the frames would take more than {@link #MAX_BYTES}: a cape's 10×16-unit front is then at most 160×256 pixels,
 * more than a cape covers on a 1440p screen in first person, so a larger copy would only cost memory and upload time.</li>
 * <li>Downscaling is a box filter weighted by alpha (a transparent pixel adds no colour; a pixel whose average alpha rounds to
 * 0 is transparent black, as the Store's own strips keep it).</li>
 * </ul>
 * Pure Java; version-neutral.
 */
public final class CapeFrames {
	/** The most pixels per atlas unit frames are kept and drawn at. */
	public static final int MAX_SCALE = 16;
	/**
	 * The most one cape's frames may take (24 MiB): up to 65 frames at 16 px per unit (a standard animation at full size, an HD
	 * one halved, as its poster is); a longer one at 8 px per unit (120 frames take 11 MiB there).
	 */
	public static final long MAX_BYTES = 24L * 1024 * 1024;
	private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	/** How frames are kept: the layout an engine's texture writes take. */
	public enum Layout {
		/** {@code 0xAARRGGBB} ints: Minecraft 1.8.9's textures ({@code GL_BGRA}, {@code GL_UNSIGNED_INT_8_8_8_8_REV}). */
		ARGB,
		/**
		 * The bytes R, G, B, A in memory, read as this machine's ints ({@code NativeImage}'s pixels, {@code 0xAABBGGRR} on a
		 * little-endian machine): copied into a native image as they are.
		 */
		RGBA_BYTES;

		/** {@code argb} in this layout. */
		public int from(int argb) {
			if (this == ARGB) {
				return argb;
			}
			return LITTLE_ENDIAN ? argb & 0xFF00FF00 | argb >>> 16 & 0xFF | (argb & 0xFF) << 16 : argb << 8 | argb >>> 24;
		}
	}

	private CapeFrames() {
	}

	/** The scale {@code animation}'s frames are kept at: its own, at most {@link #MAX_SCALE}, halved until they fit {@code maxBytes}. */
	public static int keptScale(CapeAnimation animation, long maxBytes) {
		int scale = Math.min(animation.scale(), MAX_SCALE);
		while (scale > 1 && bytes(animation.frameCount(), scale) > maxBytes) {
			scale /= 2;
		}
		return scale;
	}

	/** What {@code frames} frames take at {@code scale} pixels per unit. */
	public static long bytes(int frames, int scale) {
		return (long) frames * CapeAnimation.ATLAS_WIDTH * scale * CapeAnimation.ATLAS_HEIGHT * scale * 4;
	}

	/**
	 * Copies the {@code count} frames of a decoded strip ({@code argb}: frames {@code ATLAS_WIDTH × scale} wide stacked top to
	 * bottom, each {@code ATLAS_HEIGHT × scale} tall) into {@code out[first …]}, each at {@code keptScale} in {@code layout}.
	 * {@code scale} must be {@code keptScale} times a power of two.
	 */
	public static void slice(int[] argb, int scale, int first, int count, int keptScale, Layout layout, int[][] out) {
		int width = CapeAnimation.ATLAS_WIDTH * scale;
		int height = CapeAnimation.ATLAS_HEIGHT * scale;
		for (int k = 0; k < count; k++) {
			out[first + k] = scaled(argb, width, k * height, scale / keptScale, layout);
		}
	}

	/**
	 * The frame of {@code argb} ({@code width} wide) whose top row is {@code top}, a frame {@code ATLAS_WIDTH × ATLAS_HEIGHT}
	 * units of {@code factor × unit} pixels each, box-filtered down by {@code factor}, in {@code layout}.
	 */
	static int[] scaled(int[] argb, int width, int top, int factor, Layout layout) {
		int w = width / factor;
		int h = CapeAnimation.ATLAS_HEIGHT * (width / CapeAnimation.ATLAS_WIDTH) / factor;
		int[] frame = new int[w * h];
		if (factor == 1) {
			for (int y = 0; y < h; y++) {
				int row = (top + y) * width;
				for (int x = 0; x < w; x++) {
					frame[y * w + x] = layout.from(argb[row + x]);
				}
			}
			return frame;
		}
		int n = factor * factor;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int a = 0;
				int r = 0;
				int g = 0;
				int b = 0;
				for (int j = 0; j < factor; j++) {
					int row = (top + y * factor + j) * width + x * factor;
					for (int i = 0; i < factor; i++) {
						int p = argb[row + i];
						int pa = p >>> 24;
						a += pa;
						r += (p >>> 16 & 0xFF) * pa;
						g += (p >>> 8 & 0xFF) * pa;
						b += (p & 0xFF) * pa;
					}
				}
				int alpha = (a + n / 2) / n;
				int pixel = alpha == 0 ? 0 : alpha << 24 | (r + a / 2) / a << 16 | (g + a / 2) / a << 8 | (b + a / 2) / a;
				frame[y * w + x] = layout.from(pixel);
			}
		}
		return frame;
	}
}

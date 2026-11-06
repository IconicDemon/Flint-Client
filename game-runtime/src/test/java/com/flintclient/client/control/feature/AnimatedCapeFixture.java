package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.CapeAnimation;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The Flint Store's own animated-cape fixtures (Store scripts/e2e-animated-capes.mjs, "TEST · Animated Standard" and "TEST ·
 * Animated HD"), made the same way here: every frame its own colours and duration, the top row and sides grey, a fully
 * transparent unit and a half-transparent white one on the front, strips of {@code floor(2048 / height)} frames, the poster
 * frame 0 at the top left of a 64×32-per-unit texture (HD halved, nearest pixel), and the manifest as the Store's public
 * lookup serves it (Store src/lib/server/api.ts {@code withAssetUrls}: a {@code url} beside every {@code path}). No production
 * cape exists yet (the live catalog is empty), so these are the shape the game is checked against.
 */
public final class AnimatedCapeFixture {
	/** "TEST · Animated Standard": 352×272 frames, a WebP upload. */
	public static final int[] STANDARD = {40, 80, 120, 60, 260, 50, 90, 150, 70, 110};
	/** "TEST · Animated HD": 704×544 frames, an AVIF upload. */
	public static final int[] HD = {60, 120, 90, 150, 45, 260, 80, 100};
	/** Where the Store keeps real textures (a Supabase project's public bucket; StoreEndpoint allows it). */
	public static final String BUCKET = "https://abcdefghijklmnopqrst.supabase.co/storage/v1/object/public/cosmetic-assets/";
	private static final int[] HOLE = {5, 8};
	private static final int[] HALF = {6, 8};

	private AnimatedCapeFixture() {
	}

	/** The e2e script's {@code hsl}: RGB 0–255. */
	static int[] hsl(double h, double s, double l) {
		double a = s * Math.min(l, 1 - l);
		int[] rgb = new int[3];
		int[] ns = {0, 8, 4};
		for (int c = 0; c < 3; c++) {
			double k = (ns[c] + h / 30) % 12;
			rgb[c] = (int) Math.round(255 * (l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1))));
		}
		return rgb;
	}

	private static int argb(int[] rgb, int alpha) {
		return alpha << 24 | rgb[0] << 16 | rgb[1] << 8 | rgb[2];
	}

	/** Frame {@code i} of {@code n}: the colour of atlas unit ({@code ux}, {@code uy}), ARGB. */
	public static int unitColor(int i, int n, int ux, int uy) {
		if (uy == 0) {
			return ux >= 1 && ux <= 10 ? 0xFFB4B4B4 : ux >= 11 && ux <= 20 ? 0xFF5A5A5A : 0;
		}
		if (ux == 0) {
			return 0xFF787878;
		}
		if (ux == 11) {
			return 0xFF8C8C8C;
		}
		if (ux >= 12) {
			return argb(hsl(i * 360.0 / n + 180, 0.6, 0.35), 255);
		}
		if (ux == HOLE[0] && uy == HOLE[1]) {
			return 0;
		}
		if (ux == HALF[0] && uy == HALF[1]) {
			return 0x80FFFFFF;
		}
		return argb(hsl(i * 360.0 / n, 0.85, 0.5), 255);
	}

	/** Frame {@code i} of {@code n} at {@code scale} pixels per unit (22·scale × 17·scale, ARGB). */
	public static int[] frame(int i, int n, int scale) {
		int w = CapeAnimation.ATLAS_WIDTH * scale;
		int h = CapeAnimation.ATLAS_HEIGHT * scale;
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				px[y * w + x] = unitColor(i, n, x / scale, y / scale);
			}
		}
		return px;
	}

	/** The strips of an animation of {@code durations.length} frames at {@code scale}: PNGs of frames stacked top to bottom. */
	public static List<byte[]> strips(int scale, int frames) {
		int w = CapeAnimation.ATLAS_WIDTH * scale;
		int h = CapeAnimation.ATLAS_HEIGHT * scale;
		int per = CapeAnimation.framesPerStrip(h);
		List<byte[]> strips = new ArrayList<>();
		for (int first = 0; first < frames; first += per) {
			int count = Math.min(per, frames - first);
			int[] px = new int[w * h * count];
			for (int k = 0; k < count; k++) {
				System.arraycopy(frame(first + k, frames, scale), 0, px, k * w * h, w * h);
			}
			strips.add(CapePng.encodeRgba(w, h * count, px));
		}
		return strips;
	}

	/** The poster: frame 0 at the top left of 64×32 per unit at {@code min(scale, 16)}, HD halved by the nearest pixel. */
	public static int[] poster(int scale, int frames) {
		int[] frame0 = frame(0, frames, scale);
		int s = Math.min(scale, 16);
		int step = scale / s;
		int fw = CapeAnimation.ATLAS_WIDTH * scale;
		int pw = 64 * s;
		int[] px = new int[pw * 32 * s];
		for (int y = 0; y < CapeAnimation.ATLAS_HEIGHT * s; y++) {
			for (int x = 0; x < CapeAnimation.ATLAS_WIDTH * s; x++) {
				px[y * pw + x] = frame0[y * step * fw + x * step];
			}
		}
		return px;
	}

	/** The manifest as the Store's public lookup serves it, every strip at {@code base + "animated/<sha256>.png"}. */
	public static String animationJson(int scale, int[] durations, List<byte[]> strips, String base) {
		int per = CapeAnimation.framesPerStrip(CapeAnimation.ATLAS_HEIGHT * scale);
		StringJoiner ds = new StringJoiner(",");
		int total = 0;
		for (int d : durations) {
			ds.add(Integer.toString(d));
			total += d;
		}
		StringJoiner ss = new StringJoiner(",");
		for (int k = 0; k < strips.size(); k++) {
			byte[] png = strips.get(k);
			String hash = StoreCapes.sha256(png);
			ss.add("{\"path\":\"animated/" + hash + ".png\",\"sha256\":\"" + hash + "\",\"bytes\":" + png.length + ",\"frames\":"
				+ Math.min(per, durations.length - k * per) + ",\"url\":\"" + base + "animated/" + hash + ".png\"}");
		}
		return "{\"v\":1,\"layout\":\"cape-atlas-22x17\",\"width\":" + 22 * scale + ",\"height\":" + 17 * scale + ",\"scale\":" + scale
			+ ",\"frame_count\":" + durations.length + ",\"durations_ms\":[" + ds + "],\"duration_ms\":" + total + ",\"frames_per_strip\":"
			+ per + ",\"strips\":[" + ss + "],\"source\":{\"format\":\"webp\",\"sha256\":\"" + "ab".repeat(32) + "\",\"bytes\":4096}}";
	}
}

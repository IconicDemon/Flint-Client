package com.flintclient.client.render;

import com.flintclient.client.FlintLog;
import com.flintclient.client.platform.Images;
import com.flintclient.client.platform.Services;
import com.flintclient.client.platform.Texture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The mod cards' pictures: white masks ({@code flintclient:textures/mods/<mod key>.png}) tinted as they're drawn, grey
 * while a mod is off and white while it's on. Each is trimmed to its art and fitted into the box it's shown in keeping
 * its shape, so a wide picture (a bar, a label) shows as wide as a square one is tall. It is scaled down once for the
 * size it's shown at, averaging the pixels each new one covers, so it stays crisp at any interface scale; a new size (a
 * resized window) scales it again. A mod without a picture returns false and keeps its line icon. Pictures are read and
 * trimmed ahead on a worker ({@link #preload}), so the first opening of the menu only uploads them. Render thread, except
 * {@link #preload}'s reading.
 */
public final class ModIcons {
	/** Each key's picture, or empty for a mod without one; filled on a worker by {@link #preload} or on first draw. */
	private static final Map<String, Optional<Entry>> ENTRIES = new ConcurrentHashMap<>();
	/** Below this coverage a pixel is margin, not art. */
	private static final float EMPTY = 0.04F;
	/**
	 * Uploading pictures a worker finished takes at most this long in one frame (a hundred finishing together would stall
	 * it); the rest go up over the next frames, while the menu fades in. Uploads further apart than the gap are in another
	 * frame. Render thread.
	 */
	private static final long UPLOAD_BUDGET_NANOS = 4_000_000L;
	private static final long NEW_FRAME_GAP_NANOS = 3_000_000L;
	private static long uploadSpent;
	private static long lastUploadEnd;

	private ModIcons() {
	}

	/** One picture: its art as coverage (0..1 per pixel, trimmed) and the texture it is shown with at {@link #w} × {@link #h}. */
	private static final class Entry {
		final float[] coverage;
		final int sourceW;
		final int sourceH;
		final String path;
		@Nullable Texture texture;
		int w;
		int h;
		/** The size a worker is scaling it to, or last scaled it to. Render thread. */
		int jobW;
		int jobH;
		/** A worker is scaling it ({@link #prepare}). */
		volatile boolean running;
		/** The worker's result for {@link #jobW} × {@link #jobH}, waiting to be uploaded on the render thread. */
		volatile int @Nullable [] ready;
		/** Scaling on a worker failed once: it is scaled where it is drawn from then on, as before. */
		volatile boolean scaleHere;

		Entry(float[] coverage, int sourceW, int sourceH, String path) {
			this.coverage = coverage;
			this.sourceW = sourceW;
			this.sourceH = sourceH;
			this.path = path;
		}
	}

	/**
	 * Draws {@code key}'s picture centred on ({@code cx}, {@code cy}), as large as fits {@code maxW} × {@code maxH} px with
	 * its shape kept, in {@code argb}; false when the mod has none.
	 */
	public static boolean draw(Canvas c, String key, int cx, int cy, int maxW, int maxH, int argb) {
		Entry entry = entry(key);
		if (entry == null || maxW <= 0 || maxH <= 0) {
			return false;
		}
		float aspect = entry.sourceW / (float) entry.sourceH;
		int h = Math.max(1, Math.round(Math.min(maxH, maxW / aspect)));
		int w = Math.max(1, Math.round(h * aspect));
		// Never above the art's own size (a picture smaller than its box is shown at its own pixels).
		int tw = Math.min(w, entry.sourceW);
		int th = Math.min(h, entry.sourceH);
		if (entry.w != tw || entry.h != th || entry.texture == null) {
			prepare(entry, tw, th);
			if (entry.texture == null) {
				// Its first size is being scaled on a worker: it shows a frame or two from now, while its card fades in.
				return true;
			}
		}
		c.texture(entry.texture, cx - w / 2, cy - h / 2, w, h, entry.w, entry.h, argb);
		return true;
	}

	/**
	 * Brings the entry to {@code tw × th}: uploads a worker's result once it's back, else has a worker scale it (one job at
	 * a time per picture), so the menu's first frame doesn't scale a hundred pictures on the render thread. Meanwhile the
	 * size it had is stretched to the box (a resized window), or nothing is drawn (its first frame). Render thread.
	 */
	private static void prepare(Entry entry, int tw, int th) {
		if (entry.scaleHere) {
			upload(entry, tw, th, scaledPixels(entry, tw, th));
			return;
		}
		if (entry.running) {
			return;
		}
		int[] done = entry.ready;
		if (done != null) {
			if (entry.jobW == tw && entry.jobH == th) {
				long now = System.nanoTime();
				if (now - lastUploadEnd > NEW_FRAME_GAP_NANOS) {
					uploadSpent = 0;
				}
				if (uploadSpent >= UPLOAD_BUDGET_NANOS) {
					// This frame has uploaded its share; the rest go up in the next frames.
					return;
				}
				entry.ready = null;
				upload(entry, tw, th, done);
				lastUploadEnd = System.nanoTime();
				uploadSpent += lastUploadEnd - now;
				return;
			}
			entry.ready = null;
		}
		entry.jobW = tw;
		entry.jobH = th;
		entry.running = true;
		try {
			Services.background(() -> {
				try {
					entry.ready = scaledPixels(entry, tw, th);
				} catch (RuntimeException | Error e) {
					entry.scaleHere = true;
				} finally {
					entry.running = false;
				}
			});
		} catch (RuntimeException e) {
			entry.running = false;
			entry.scaleHere = true;
		}
	}

	/**
	 * Draws a full-colour picture ({@code flintclient:textures/mods/<file>.png}, shown as it is, not tinted) centred on
	 * ({@code cx}, {@code cy}), as large as fits {@code maxW} × {@code maxH} px with its shape kept; false when there is no
	 * such file. Pixel art stays crisp: drawn at a whole number of screen pixels per art pixel when that comes close to the
	 * fitted size, else averaged down from the file.
	 */
	public static boolean drawArt(Canvas c, String file, int cx, int cy, int maxW, int maxH) {
		Art art = ARTS.computeIfAbsent(file, ModIcons::readArt).orElse(null);
		if (art == null || maxW <= 0 || maxH <= 0) {
			return false;
		}
		int nativeW = art.sourceW / art.cell;
		int nativeH = art.sourceH / art.cell;
		float fitted = Math.min(maxW / (float) nativeW, maxH / (float) nativeH);
		// A few percent over the box is worth whole art pixels.
		int whole = (int) Math.floor(fitted + 0.08F);
		int w;
		int h;
		if (whole >= 1 && whole >= fitted * 0.8F) {
			w = nativeW * whole;
			h = nativeH * whole;
		} else {
			w = Math.max(1, Math.round(nativeW * fitted));
			h = Math.max(1, Math.round(nativeH * fitted));
		}
		if (art.w != w || art.h != h || art.texture == null) {
			scaleArt(art, w, h);
		}
		c.texture(art.texture, cx - w / 2, cy - h / 2, w, h, art.w, art.h, 0xFFFFFFFF);
		return true;
	}

	/** Full-colour pictures by file, or empty for a missing one. */
	private static final Map<String, Optional<Art>> ARTS = new ConcurrentHashMap<>();

	/** One full-colour picture: its pixels (ARGB), the size of one art pixel in the file, and its texture at {@link #w} × {@link #h}. */
	private static final class Art {
		final int[] argb;
		final int sourceW;
		final int sourceH;
		final int cell;
		final String path;
		@Nullable Texture texture;
		int w;
		int h;

		Art(int[] argb, int sourceW, int sourceH, int cell, String path) {
			this.argb = argb;
			this.sourceW = sourceW;
			this.sourceH = sourceH;
			this.cell = cell;
			this.path = path;
		}
	}

	private static Optional<Art> readArt(String file) {
		Art art = null;
		try {
			Pixels image = Images.read("textures/mods/" + file + ".png");
			if (image != null) {
				int w = image.width();
				int h = image.height();
				int[] argb = image.argb();
				art = new Art(argb, w, h, cell(argb, w, h), "mod_art/" + file);
			}
		} catch (Exception e) {
			FlintLog.LOGGER.debug("Flint: no card art {}", file, e);
		}
		return Optional.ofNullable(art);
	}

	/** The side of one art pixel in an upscaled pixel-art file: the largest size every run of equal pixels is a multiple of (1 for any other picture). */
	static int cell(int[] argb, int w, int h) {
		int cell = gcd(w, h);
		for (int py = 0; py < h && cell > 1; py++) {
			int run = 1;
			for (int px = 1; px <= w; px++) {
				if (px < w && argb[py * w + px] == argb[py * w + px - 1]) {
					run++;
				} else {
					cell = gcd(cell, run);
					run = 1;
				}
			}
		}
		for (int px = 0; px < w && cell > 1; px++) {
			int run = 1;
			for (int py = 1; py <= h; py++) {
				if (py < h && argb[py * w + px] == argb[(py - 1) * w + px]) {
					run++;
				} else {
					cell = gcd(cell, run);
					run = 1;
				}
			}
		}
		return Math.max(1, cell);
	}

	private static int gcd(int a, int b) {
		return b == 0 ? a : gcd(b, a % b);
	}

	/** Remakes the picture's texture at {@code w} × {@code h} px: whole art pixels repeated, or the file's pixels averaged by area. */
	private static void scaleArt(Art art, int w, int h) {
		int nativeW = art.sourceW / art.cell;
		int nativeH = art.sourceH / art.cell;
		int[] pixels = new int[w * h];
		if (w % nativeW == 0 && h % nativeH == 0 && w / nativeW == h / nativeH) {
			int k = w / nativeW;
			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					pixels[py * w + px] = art.argb[(py / k * art.cell + art.cell / 2) * art.sourceW + px / k * art.cell + art.cell / 2];
				}
			}
		} else {
			float[][] channels = new float[4][art.argb.length];
			for (int i = 0; i < art.argb.length; i++) {
				int p = art.argb[i];
				float a = (p >>> 24) / 255.0F;
				channels[0][i] = a;
				// Colour weighted by coverage, so a see-through edge doesn't darken what it's averaged with.
				channels[1][i] = (p >> 16 & 0xFF) * a;
				channels[2][i] = (p >> 8 & 0xFF) * a;
				channels[3][i] = (p & 0xFF) * a;
			}
			int tw = Math.min(w, art.sourceW);
			int th = Math.min(h, art.sourceH);
			float[][] scaled = new float[4][];
			for (int ch = 0; ch < 4; ch++) {
				scaled[ch] = area(channels[ch], art.sourceW, art.sourceH, tw, th);
			}
			pixels = new int[tw * th];
			for (int i = 0; i < tw * th; i++) {
				float a = Math.clamp(scaled[0][i], 0, 1);
				int r = a > 0 ? Math.clamp(Math.round(scaled[1][i] / a), 0, 255) : 0;
				int g = a > 0 ? Math.clamp(Math.round(scaled[2][i] / a), 0, 255) : 0;
				int b = a > 0 ? Math.clamp(Math.round(scaled[3][i] / a), 0, 255) : 0;
				pixels[i] = Math.round(a * 255) << 24 | r << 16 | g << 8 | b;
			}
			w = tw;
			h = th;
		}
		art.texture = Texture.upload(art.path, w, h, pixels, true);
		art.w = w;
		art.h = h;
	}

	/** Reads and trims {@code keys}' pictures on a worker (the texture is still made on the render thread when first drawn). */
	public static void preload(List<String> keys) {
		Services.background(() -> {
			for (String key : keys) {
				entry(key);
			}
		});
	}

	private static @Nullable Entry entry(String key) {
		return ENTRIES.computeIfAbsent(key, ModIcons::read).orElse(null);
	}

	/** The picture file's art; safe on a worker (a resource read and an image decode, nothing on the GPU). */
	private static Optional<Entry> read(String key) {
		Entry entry = null;
		try {
			Pixels image = Images.read("textures/mods/" + key + ".png");
			if (image != null) {
				entry = trimmed(image, "mod_icon/" + key);
			}
		} catch (Exception e) {
			FlintLog.LOGGER.debug("Flint: no card picture for {}", key, e);
		}
		return Optional.ofNullable(entry);
	}

	/** The picture's art (its coverage inside the box around every pixel that shows), or null for an empty picture. */
	private static @Nullable Entry trimmed(Pixels image, String path) {
		int iw = image.width();
		int ih = image.height();
		int x0 = iw;
		int y0 = ih;
		int x1 = -1;
		int y1 = -1;
		for (int py = 0; py < ih; py++) {
			for (int px = 0; px < iw; px++) {
				if ((image.get(px, py) >>> 24) / 255.0F > EMPTY) {
					x0 = Math.min(x0, px);
					y0 = Math.min(y0, py);
					x1 = Math.max(x1, px);
					y1 = Math.max(y1, py);
				}
			}
		}
		if (x1 < 0) {
			return null;
		}
		int w = x1 - x0 + 1;
		int h = y1 - y0 + 1;
		float[] coverage = new float[w * h];
		for (int py = 0; py < h; py++) {
			for (int px = 0; px < w; px++) {
				coverage[py * w + px] = (image.get(x0 + px, y0 + py) >>> 24) / 255.0F;
			}
		}
		return new Entry(coverage, w, h, path);
	}

	/** The entry's art at {@code tw} × {@code th} px (each no larger than the art): each new pixel the average of those it covers. Any thread. */
	private static int[] scaledPixels(Entry entry, int tw, int th) {
		float[] scaled = area(entry.coverage, entry.sourceW, entry.sourceH, tw, th);
		int[] pixels = new int[tw * th];
		for (int py = 0; py < th; py++) {
			for (int px = 0; px < tw; px++) {
				int a = Math.round(Math.clamp(scaled[py * tw + px], 0, 1) * 255);
				pixels[py * tw + px] = a << 24 | 0xFFFFFF;
			}
		}
		return pixels;
	}

	/** Remakes the entry's texture from {@code pixels} ({@code tw} × {@code th}). Render thread. */
	private static void upload(Entry entry, int tw, int th, int[] pixels) {
		entry.texture = Texture.upload(entry.path, tw, th, pixels, true);
		entry.w = tw;
		entry.h = th;
	}

	/** An sw × sh coverage map averaged down to dw × dh (each no larger), counting each source pixel by how much of it a new one covers. */
	static float[] area(float[] source, int sw, int sh, int dw, int dh) {
		if (dw == sw && dh == sh) {
			return source.clone();
		}
		float sx = sw / (float) dw;
		float sy = sh / (float) dh;
		float[] rows = new float[sh * dw];
		for (int y = 0; y < sh; y++) {
			for (int x = 0; x < dw; x++) {
				rows[y * dw + x] = span(source, y * sw, 1, x * sx, Math.min(sw, (x + 1) * sx)) / sx;
			}
		}
		float[] out = new float[dw * dh];
		for (int x = 0; x < dw; x++) {
			for (int y = 0; y < dh; y++) {
				out[y * dw + x] = span(rows, x, dw, y * sy, Math.min(sh, (y + 1) * sy)) / sy;
			}
		}
		return out;
	}

	/** The sum of {@code values[base + i * stride]} over [from, to), partial pixels counted by their covered part. */
	private static float span(float[] values, int base, int stride, float from, float to) {
		float sum = 0;
		int first = (int) Math.floor(from);
		int last = (int) Math.ceil(to);
		for (int i = first; i < last; i++) {
			float part = Math.min(to, i + 1) - Math.max(from, i);
			if (part > 0) {
				sum += values[base + i * stride] * part;
			}
		}
		return sum;
	}
}

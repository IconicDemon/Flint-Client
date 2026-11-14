package com.flintclient.client.module.social;

import com.flintclient.client.module.social.MenuLink.Art;
import com.flintclient.client.platform.Images;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Pixels;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The main menu's pictures from the Flint app (partner art, showcase slides, reward capes), kept for the game session: each is
 * asked for once, decoded and scaled down to the size it is shown at on {@link MenuFeeds}' worker, and uploaded as a texture
 * the first time it is drawn. At most {@link #MAX_ENTRIES} are kept; the least recently drawn goes first (its texture freed).
 * A picture that couldn't be had stays failed for the session: its place shows Flint's own art. Render thread, except the
 * worker's decoding.
 */
public final class MenuArt {
	private static final int MAX_ENTRIES = 48;
	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(16, 0.75F, true);
	private static int uploads;

	private MenuArt() {
	}

	/** How a picture is fetched: a partner's by checksum, a slide's by id, a reward's by id. */
	public interface Fetch {
		byte[] fetch(MenuLink link) throws com.flintclient.client.module.account.LauncherLink.LinkException;
	}

	/** One picture: loading, ready (its texture) or failed. */
	public static final class Entry {
		private volatile @Nullable Pixels pixels;
		private volatile boolean failed;
		private boolean smooth = true;
		private @Nullable Texture texture;
		private int width;
		private int height;

		/** Its texture once it has come (uploaded at the first call), else null. Render thread. */
		public @Nullable Texture texture() {
			if (texture == null) {
				Pixels ready = pixels;
				if (ready != null) {
					texture = Texture.upload("menu_art/" + (uploads++), ready.width(), ready.height(), ready.argb(), smooth);
					width = ready.width();
					height = ready.height();
					pixels = null;
				}
			}
			return texture;
		}

		public int width() {
			return width;
		}

		public int height() {
			return height;
		}

		public boolean failed() {
			return failed;
		}

		void release() {
			if (texture != null) {
				texture.release();
				texture = null;
			}
		}
	}

	/** A partner's or a slide's picture ({@link Art}: PNG or JPEG), scaled to fit {@code maxW × maxH}. */
	public static Entry art(String key, int maxW, int maxH, ArtFetch fetch) {
		return entry(key, maxW, maxH, link -> {
			Art art = fetch.fetch(link);
			return art.bytes();
		});
	}

	/** A PNG picture (a reward cape's texture), kept at its own size and sampled as it is (pixel art). */
	public static Entry png(String key, Fetch fetch) {
		boolean known = ENTRIES.containsKey(key);
		Entry entry = entry(key, 0, 0, fetch);
		if (!known) {
			entry.smooth = false;
		}
		return entry;
	}

	/** {@link Art} fetching. */
	public interface ArtFetch {
		Art fetch(MenuLink link) throws com.flintclient.client.module.account.LauncherLink.LinkException;
	}

	private static Entry entry(String key, int maxW, int maxH, Fetch fetch) {
		Entry entry = ENTRIES.get(key);
		if (entry != null) {
			return entry;
		}
		Entry made = new Entry();
		ENTRIES.put(key, made);
		trim();
		MenuFeeds.get().run(link -> {
			byte[] bytes = fetch.fetch(link);
			Pixels decoded = Images.decode(bytes);
			if (decoded == null) {
				throw new com.flintclient.client.module.account.LauncherLink.LinkException("unreadable picture", false);
			}
			return maxW > 0 && maxH > 0 ? fitPixels(decoded, maxW, maxH) : decoded;
		}, pixels -> made.pixels = pixels, why -> made.failed = true);
		return made;
	}

	private static void trim() {
		Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator();
		while (ENTRIES.size() > MAX_ENTRIES && it.hasNext()) {
			it.next().getValue().release();
			it.remove();
		}
	}

	/** {@code image} scaled down (never up) to fit {@code maxW × maxH}, keeping its shape: each new pixel the average it covers. */
	public static Pixels fitPixels(Pixels image, int maxW, int maxH) {
		float s = Math.min(1.0F, Math.min(maxW / (float) image.width(), maxH / (float) image.height()));
		if (s >= 1.0F) {
			return image;
		}
		int w = Math.max(1, Math.round(image.width() * s));
		int h = Math.max(1, Math.round(image.height() * s));
		int[] out = new int[w * h];
		int[] src = image.argb();
		int sw = image.width();
		int sh = image.height();
		for (int y = 0; y < h; y++) {
			int y0 = y * sh / h;
			int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
			for (int x = 0; x < w; x++) {
				int x0 = x * sw / w;
				int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
				long a = 0;
				long r = 0;
				long g = 0;
				long b = 0;
				for (int yy = y0; yy < y1; yy++) {
					for (int xx = x0; xx < x1; xx++) {
						int p = src[yy * sw + xx];
						int alpha = p >>> 24;
						a += alpha;
						// Colours weighted by coverage, so a transparent edge doesn't darken what's next to it.
						r += ((p >> 16) & 0xFF) * alpha;
						g += ((p >> 8) & 0xFF) * alpha;
						b += (p & 0xFF) * alpha;
					}
				}
				int n = (y1 - y0) * (x1 - x0);
				int oa = (int) (a / n);
				int or = a == 0 ? 0 : (int) (r / a);
				int og = a == 0 ? 0 : (int) (g / a);
				int ob = a == 0 ? 0 : (int) (b / a);
				out[y * w + x] = oa << 24 | or << 16 | og << 8 | ob;
			}
		}
		return new Pixels(w, h, out);
	}
}

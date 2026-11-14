package com.flintclient.client.ui;

import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * The background behind Flint's main menu and its pages, on Minecraft 1.8.9: Flint's menu artwork (the same picture as on
 * every line), scaled to cover the window (aspect kept, centred, cropped only on the overflowing side), under a light dark
 * veil. One textured quad and one fill. 1.8.9's resource packs don't see a mod's own files, so the artwork is Flint's own
 * texture: decoded on a worker while the game starts ({@link #preload}), uploaded at the first frame that draws it, and kept
 * (small next to 1.8.9's own), so nothing is released when a world starts. Until it is there (or in a jar without it) the
 * veil's colour is drawn alone.
 */
public final class Backdrop {
	private static final String ARTWORK = "textures/gui/menu_background.png";
	private static final int ARTWORK_W = 1600;
	private static final int ARTWORK_H = 900;

	/** Veil strength on the main menu (the artwork is already dark) and on text-heavy pages. */
	public static final float MENU = 0.16F;
	public static final float PAGE = 0.42F;

	private static final float[] CROP = new float[4];
	private static @Nullable Texture artwork;
	private static @Nullable CompletableFuture<int @Nullable []> decoding;
	private static boolean missing;

	private Backdrop() {
	}

	/** The part of the artwork shown in a {@code w}×{@code h} window, as {u, v, width, height} in artwork pixels, into {@code into}. */
	static float[] cover(int w, int h, float[] into) {
		float scale = Math.max(w / (float) ARTWORK_W, h / (float) ARTWORK_H);
		float visibleW = w / scale;
		float visibleH = h / scale;
		into[0] = (ARTWORK_W - visibleW) / 2.0F;
		into[1] = (ARTWORK_H - visibleH) / 2.0F;
		into[2] = visibleW;
		into[3] = visibleH;
		return into;
	}

	public static void paint(Canvas c, int widthGui, int heightGui, float veil) {
		// One unit past each edge, as on every line (a screen's size in units is the window's rounded down).
		int w = c.px(widthGui + 1);
		int h = c.px(heightGui + 1);
		Texture texture = artwork();
		if (texture != null) {
			float[] crop = cover(w, h, CROP);
			c.frame().blit(texture, 0, 0, crop[0], crop[1], w, h, Math.round(crop[2]), Math.round(crop[3]), ARTWORK_W, ARTWORK_H, c.tint(0xFFFFFFFF));
			c.fill(0, 0, w, h, Palette.withAlpha(Palette.BACKGROUND_TOP, veil));
		} else {
			c.fill(0, 0, w, h, Palette.withAlpha(Palette.BACKGROUND_TOP, 1.0F));
		}
	}

	/** The artwork's texture once its pixels are decoded (uploaded here, on the render thread), else null. */
	private static @Nullable Texture artwork() {
		if (artwork != null || missing) {
			return artwork;
		}
		preload();
		CompletableFuture<int @Nullable []> pending = decoding;
		if (pending == null || !pending.isDone()) {
			return null;
		}
		int[] argb = pending.getNow(null);
		decoding = null;
		if (argb == null) {
			missing = true;
			return null;
		}
		artwork = Texture.upload("backdrop", ARTWORK_W, ARTWORK_H, argb, true);
		return artwork;
	}

	/** Starts decoding the artwork on a worker (from Flint's start, so the first menu frame doesn't wait for it). Any thread. */
	public static synchronized void preload() {
		if (artwork != null || missing || decoding != null) {
			return;
		}
		CompletableFuture<int @Nullable []> future = new CompletableFuture<>();
		decoding = future;
		Thread worker = new Thread(() -> future.complete(decode()), "Flint menu artwork");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	/** The artwork's ARGB pixels at its full size, or null when this jar doesn't carry it (or it can't be read). */
	private static int @Nullable [] decode() {
		try (InputStream in = Backdrop.class.getResourceAsStream("/assets/flintclient/" + ARTWORK)) {
			BufferedImage image = in == null ? null : ImageIO.read(in);
			if (image == null || image.getWidth() != ARTWORK_W || image.getHeight() != ARTWORK_H) {
				return null;
			}
			return image.getRGB(0, 0, ARTWORK_W, ARTWORK_H, null, 0, ARTWORK_W);
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	/** Nothing to free on 1.8.9 (see the class comment). */
	public static void release() {
	}
}

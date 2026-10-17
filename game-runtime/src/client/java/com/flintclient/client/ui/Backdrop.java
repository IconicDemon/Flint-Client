package com.flintclient.client.ui;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The background behind every Flint screen: Flint's menu artwork, scaled to cover the window (aspect
 * ratio kept, centred, cropped only on the overflowing side; never stretched, never letterboxed),
 * under a light dark veil so text and controls stay readable. One textured quad and one fill.
 *
 * <p>While a world is played the artwork's texture can be released (the {@code menuResources} module).
 * When the world is left it's decoded again on a worker thread, and the first menu frame uploads that
 * result, exactly as Minecraft would have loaded it; if it isn't ready, drawing loads it the usual way.
 */
public final class Backdrop {
	private static final Identifier ARTWORK = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "textures/gui/menu_background.png");
	private static final int ARTWORK_W = 1600;
	private static final int ARTWORK_H = 900;
	/** The artwork's texture as Minecraft loads it: one RGBA8 level. */
	private static final long ARTWORK_BYTES = GpuResourceTracker.rgba8(ARTWORK_W, ARTWORK_H);

	/** Veil strength on the main menu (the artwork is already dark) and on text-heavy pages. */
	public static final float MENU = 0.16F;
	public static final float PAGE = 0.42F;

	/** The crop of the current frame, reused (render thread only). */
	private static final float[] CROP = new float[4];
	/** The texture was released and hasn't been put back yet. */
	private static boolean released;
	/** Counted as a live texture (from the first draw that loads it until it's released). */
	private static boolean counted;
	private static @Nullable CompletableFuture<TextureContents> preloaded;
	/** The resource packs the preload read from; a reload since then makes it stale. */
	private static List<PackResources> preloadedFrom = List.of();

	private Backdrop() {
	}

	/**
	 * The part of the artwork shown in a {@code w}×{@code h} window, as {u, v, width, height} in artwork
	 * pixels: the larger scale factor fills the window and the other axis is cropped evenly.
	 */
	static float[] cover(int w, int h) {
		return cover(w, h, new float[4]);
	}

	/** {@link #cover(int, int)} into {@code into}, which is returned. */
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
		if (released) {
			restore();
		}
		if (!counted) {
			// The blit below loads the texture if restore didn't put it in place.
			counted = true;
			GpuResourceTracker.get().register(GpuResourceTracker.Kind.BACKDROP, ARTWORK_BYTES);
		}
		int w = c.px(widthGui);
		int h = c.px(heightGui);
		float[] crop = cover(w, h, CROP);
		c.graphics().blit(RenderPipelines.GUI_TEXTURED, ARTWORK, 0, 0, crop[0], crop[1], w, h,
			Math.round(crop[2]), Math.round(crop[3]), ARTWORK_W, ARTWORK_H);
		c.fill(0, 0, w, h, Palette.withAlpha(Palette.BACKGROUND_TOP, veil));
	}

	/** Frees the artwork's texture (a world was joined); drawing brings it back. Render thread. */
	public static void release() {
		dropPreload();
		Minecraft.getInstance().getTextureManager().release(ARTWORK);
		released = true;
		if (counted) {
			counted = false;
			GpuResourceTracker.get().release(GpuResourceTracker.Kind.BACKDROP, ARTWORK_BYTES);
		}
	}

	/** Starts decoding the released artwork on a worker thread (a world was left), for the next menu frame. */
	public static void preload() {
		if (!released || preloaded != null) {
			return;
		}
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		preloadedFrom = resources.listPacks().toList();
		preloaded = CompletableFuture.supplyAsync(() -> {
			try {
				return TextureContents.load(resources, ARTWORK);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, Util.backgroundExecutor());
	}

	/**
	 * Puts the preloaded artwork in place, as {@code TextureManager.getTexture} would have loaded it (same
	 * contents, same texture class and sampler). Anything else (not decoded yet, failed, or resource packs
	 * reloaded since) leaves the id unregistered, so the draw loads it synchronously as before.
	 */
	private static void restore() {
		released = false;
		CompletableFuture<TextureContents> pending = preloaded;
		List<PackResources> from = preloadedFrom;
		preloaded = null;
		preloadedFrom = List.of();
		if (pending == null) {
			return;
		}
		if (!pending.isDone() || pending.isCompletedExceptionally()) {
			pending.thenAccept(TextureContents::close);
			return;
		}
		TextureContents contents = pending.join();
		Minecraft minecraft = Minecraft.getInstance();
		if (!samePacks(from, minecraft.getResourceManager().listPacks().toList())) {
			contents.close();
			return;
		}
		SimpleTexture texture = new SimpleTexture(ARTWORK);
		texture.apply(contents);
		minecraft.getTextureManager().register(ARTWORK, texture);
	}

	private static void dropPreload() {
		if (preloaded != null) {
			preloaded.thenAccept(TextureContents::close);
			preloaded = null;
			preloadedFrom = List.of();
		}
	}

	/**
	 * A reload opens resource and server packs again (only the unchanging built-in ones are reused), so
	 * the same instances in the same order mean the same resources.
	 */
	private static boolean samePacks(List<PackResources> a, List<PackResources> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i) != b.get(i)) {
				return false;
			}
		}
		return true;
	}
}

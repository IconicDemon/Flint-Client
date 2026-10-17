package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.GpuResourceTracker;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * A photo-like picture drawn smaller than its file (the scene behind module settings previews), kept at full, half and
 * quarter size with linear filtering, so a draw never shrinks it more than twofold on the GPU and it doesn't shimmer.
 *
 * <p>Reading and shrinking happen on a worker thread ({@link #request}); only the upload is on the render thread, the
 * first frame after the work is done (nothing graphical ever runs off it). Until then {@link #best} returns null and the
 * caller draws a plain fill. {@link #release} frees the textures (the Right Shift menu closed), so nothing stays on the
 * GPU during gameplay; the next request decodes again. Render thread, except the decoding.
 */
public final class Picture {
	private static final int LEVELS = 3;

	/** A texture and its size in pixels. */
	public record Level(Identifier id, int width, int height) {
	}

	private final Identifier file;
	private final String name;
	private final Level[] levels = new Level[LEVELS];
	private @Nullable CompletableFuture<NativeImage[]> decoding;
	private boolean failed;
	private long bytes;

	/** The picture in {@code file}; {@code name} names its textures. */
	public Picture(Identifier file, String name) {
		this.file = file;
		this.name = name;
	}

	/**
	 * The smallest copy at least {@code minW × minH} pixels (the largest when none is), or null while it isn't ready (the
	 * first call starts decoding) or couldn't be read.
	 */
	public @Nullable Level best(int minW, int minH) {
		if (levels[0] == null) {
			upload();
			if (levels[0] == null) {
				return null;
			}
		}
		Level best = levels[0];
		for (int i = 1; i < LEVELS; i++) {
			Level level = levels[i];
			if (level != null && level.width() >= minW && level.height() >= minH) {
				best = level;
			}
		}
		return best;
	}

	/** Starts decoding on a worker thread, unless it is ready, decoding or failed. */
	public void request() {
		if (levels[0] != null || decoding != null || failed) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Identifier source = file;
		decoding = CompletableFuture.supplyAsync(() -> {
			try (InputStream in = minecraft.getResourceManager().open(source)) {
				NativeImage full = NativeImage.read(in);
				NativeImage[] out = new NativeImage[LEVELS];
				out[0] = full;
				for (int i = 1; i < LEVELS; i++) {
					out[i] = half(out[i - 1]);
				}
				return out;
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, Util.backgroundExecutor());
	}

	/** Uploads the decoded levels once they are ready (render thread); asks for them first if nobody has. */
	private void upload() {
		CompletableFuture<NativeImage[]> pending = decoding;
		if (pending == null) {
			request();
			return;
		}
		if (!pending.isDone()) {
			return;
		}
		decoding = null;
		NativeImage[] images;
		try {
			images = pending.join();
		} catch (RuntimeException e) {
			failed = true;
			FlintClient.LOGGER.error("Flint couldn't read {}", file, e);
			return;
		}
		for (int i = 0; i < LEVELS; i++) {
			NativeImage image = images[i];
			Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "dynamic/" + name + "_" + i);
			Minecraft.getInstance().getTextureManager().register(id, new Linear(name + " " + i, image));
			levels[i] = new Level(id, image.getWidth(), image.getHeight());
			bytes += GpuResourceTracker.rgba8(image.getWidth(), image.getHeight());
		}
		GpuResourceTracker.get().register(GpuResourceTracker.Kind.PREVIEW, bytes);
	}

	/** Frees the textures (and drops a decode still running); the next {@link #best} decodes again. Render thread. */
	public void release() {
		CompletableFuture<NativeImage[]> pending = decoding;
		decoding = null;
		if (pending != null) {
			pending.thenAccept(images -> {
				for (NativeImage image : images) {
					image.close();
				}
			});
		}
		if (levels[0] == null) {
			return;
		}
		for (int i = 0; i < LEVELS; i++) {
			Level level = levels[i];
			if (level != null) {
				Minecraft.getInstance().getTextureManager().release(level.id());
				levels[i] = null;
			}
		}
		GpuResourceTracker.get().release(GpuResourceTracker.Kind.PREVIEW, bytes);
		bytes = 0;
	}

	/** {@code source} at half its size, each pixel the average of the four it covers (an odd last row or column is kept). */
	private static NativeImage half(NativeImage source) {
		int w = Math.max(1, source.getWidth() / 2);
		int h = Math.max(1, source.getHeight() / 2);
		NativeImage out = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			int sy = Math.min(2 * y, source.getHeight() - 1);
			int sy1 = Math.min(sy + 1, source.getHeight() - 1);
			for (int x = 0; x < w; x++) {
				int sx = Math.min(2 * x, source.getWidth() - 1);
				int sx1 = Math.min(sx + 1, source.getWidth() - 1);
				out.setPixel(x, y, average(source.getPixel(sx, sy), source.getPixel(sx1, sy), source.getPixel(sx, sy1), source.getPixel(sx1, sy1)));
			}
		}
		return out;
	}

	private static int average(int a, int b, int c, int d) {
		int alpha = ((a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24) + 2) / 4;
		int red = ((a >> 16 & 255) + (b >> 16 & 255) + (c >> 16 & 255) + (d >> 16 & 255) + 2) / 4;
		int green = ((a >> 8 & 255) + (b >> 8 & 255) + (c >> 8 & 255) + (d >> 8 & 255) + 2) / 4;
		int blue = ((a & 255) + (b & 255) + (c & 255) + (d & 255) + 2) / 4;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	/** A texture sampled with linear filtering and clamped at its edges. */
	private static final class Linear extends DynamicTexture {
		Linear(String label, NativeImage image) {
			super(() -> label, image);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}

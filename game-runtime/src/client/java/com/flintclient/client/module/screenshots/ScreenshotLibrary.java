package com.flintclient.client.module.screenshots;

import com.flintclient.client.FlintClient;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Reads the profile's real screenshots (the {@code screenshots} folder Minecraft saves F2 captures to).
 * Listing and decoding happen off the render thread; images are scaled down to the size they're shown
 * at, so the gallery never holds full-size screenshots in GPU memory.
 *
 * <p>With the {@code decodeExecutor} module, decodes run one at a time on Flint's own low-priority
 * thread instead of Minecraft's shared worker pool (which also builds chunk meshes). The thread is made
 * on first use and ends after half a minute with nothing to do.
 */
public final class ScreenshotLibrary {
	private static final long DECODER_IDLE_SECONDS = 30;
	private static boolean ownDecoder;
	private static @Nullable ThreadPoolExecutor decoder;

	public record Shot(Path path, long modified, long size) {
		public String name() {
			return path.getFileName().toString();
		}
	}

	private ScreenshotLibrary() {
	}

	/** Decodes on Flint's own thread (the {@code decodeExecutor} module). Set once at start-up. */
	public static void useOwnDecoder(boolean on) {
		ownDecoder = on;
	}

	public static Path folder() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
	}

	/** PNG screenshots, newest first. */
	public static CompletableFuture<List<Shot>> list() {
		return CompletableFuture.supplyAsync(() -> {
			Path folder = folder();
			if (!Files.isDirectory(folder)) {
				return List.<Shot>of();
			}
			List<Shot> shots = new ArrayList<>();
			try (Stream<Path> files = Files.list(folder)) {
				files.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png") && Files.isRegularFile(p))
					.forEach(p -> {
						try {
							shots.add(new Shot(p, Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
						} catch (IOException ignored) {
							// A file that vanished while listing is simply skipped.
						}
					});
			} catch (IOException e) {
				FlintClient.LOGGER.warn("Couldn't list screenshots", e);
			}
			shots.sort(Comparator.comparingLong(Shot::modified).reversed());
			return shots;
		}, Util.ioPool());
	}

	/**
	 * Decodes a screenshot and scales it for a {@code boxW}×{@code boxH}-pixel box: {@code cover} fills the
	 * box exactly (cropping the overflowing side evenly), otherwise it fits inside (never enlarged).
	 * Corners are rounded to {@code radius} px in the image's own alpha, so it sits cleanly on any
	 * background. The returned image is owned by the caller; {@code size[0..1]} gets the original size.
	 * {@code wanted} is asked just before decoding starts: when it says no, nothing is read and the
	 * result is null.
	 */
	public static CompletableFuture<@Nullable NativeImage> load(Path path, int boxW, int boxH, boolean cover, int radius, int[] size, BooleanSupplier wanted) {
		return CompletableFuture.supplyAsync(() -> {
			if (!wanted.getAsBoolean()) {
				return null;
			}
			try (InputStream in = Files.newInputStream(path); NativeImage full = NativeImage.read(in)) {
				size[0] = full.getWidth();
				size[1] = full.getHeight();
				NativeImage out;
				if (cover) {
					float scale = Math.max(boxW / (float) full.getWidth(), boxH / (float) full.getHeight());
					int cropW = Math.min(full.getWidth(), Math.round(boxW / scale));
					int cropH = Math.min(full.getHeight(), Math.round(boxH / scale));
					out = downscale(full, (full.getWidth() - cropW) / 2, (full.getHeight() - cropH) / 2, cropW, cropH, boxW, boxH);
				} else {
					float fit = Math.min(1.0F, Math.min(boxW / (float) full.getWidth(), boxH / (float) full.getHeight()));
					int w = Math.max(1, Math.round(full.getWidth() * fit));
					int h = Math.max(1, Math.round(full.getHeight() * fit));
					out = downscale(full, 0, 0, full.getWidth(), full.getHeight(), w, h);
				}
				roundCorners(out, radius);
				return out;
			} catch (IOException e) {
				throw new IllegalStateException("Couldn't read " + path.getFileName(), e);
			}
		}, decodeExecutor());
	}

	/** Render thread (where decodes are requested). */
	private static Executor decodeExecutor() {
		if (!ownDecoder) {
			return Util.backgroundExecutor();
		}
		if (decoder == null) {
			decoder = new ThreadPoolExecutor(1, 1, DECODER_IDLE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
				Thread thread = new Thread(runnable, "Flint screenshot decoder");
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
			decoder.allowCoreThreadTimeOut(true);
		}
		return decoder;
	}

	/** Fades the four corners outside a radius-{@code r} arc to transparent (antialiased). */
	private static void roundCorners(NativeImage image, int r) {
		int w = image.getWidth();
		int h = image.getHeight();
		r = Math.min(r, Math.min(w, h) / 2);
		for (int py = 0; py < r; py++) {
			for (int px = 0; px < r; px++) {
				float dx = r - (px + 0.5F);
				float dy = r - (py + 0.5F);
				float coverage = Math.clamp(0.5F - ((float) Math.sqrt(dx * dx + dy * dy) - r), 0.0F, 1.0F);
				if (coverage >= 1.0F) {
					continue;
				}
				for (int[] at : new int[][] {{px, py}, {w - 1 - px, py}, {px, h - 1 - py}, {w - 1 - px, h - 1 - py}}) {
					int argb = image.getPixel(at[0], at[1]);
					image.setPixel(at[0], at[1], (Math.round((argb >>> 24) * coverage) << 24) | (argb & 0xFFFFFF));
				}
			}
		}
	}

	/** Box-filter downscale of a source region (averages every source pixel under each target pixel). */
	private static NativeImage downscale(NativeImage src, int sx0, int sy0, int sw, int sh, int w, int h) {
		NativeImage out = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			int y0 = sy0 + (int) ((long) y * sh / h);
			int y1 = Math.max(y0 + 1, sy0 + (int) ((long) (y + 1) * sh / h));
			for (int x = 0; x < w; x++) {
				int x0 = sx0 + (int) ((long) x * sw / w);
				int x1 = Math.max(x0 + 1, sx0 + (int) ((long) (x + 1) * sw / w));
				long r = 0;
				long g = 0;
				long b = 0;
				int n = 0;
				for (int sy = y0; sy < y1; sy++) {
					for (int sx = x0; sx < x1; sx++) {
						int argb = src.getPixel(sx, sy);
						r += (argb >> 16) & 255;
						g += (argb >> 8) & 255;
						b += argb & 255;
						n++;
					}
				}
				out.setPixel(x, y, 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
			}
		}
		return out;
	}
}

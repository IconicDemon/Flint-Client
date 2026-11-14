/*
 * Adapted from Custom Crosshair Mod by wjbaker (CC0 1.0; META-INF/licenses/CC0-1.0-CustomCrosshairMod.txt): its drawn
 * crosshair, a grid of pixels kept as a PNG.
 */
package com.flintclient.client.control.feature;

import com.flintclient.client.FlintLog;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Crosshair's Drawn style: a square grid of pixels, on or off (57 by 57 to start, as Custom Crosshair Mod's). Kept as a PNG
 * whose drawn pixels are opaque black and the rest clear; read back, a pixel is on unless it's clear or opaque white (the
 * same rule, so its {@code crosshair_drawn.ccmpng} reads the same). One drawing for the game ({@link #shared}), the same on
 * every Minecraft line: the Right Shift menu's pad draws on it and the crosshair is drawn from it. Pure Java apart from the
 * image file.
 */
public final class CrosshairDrawing {
	public static final int DEFAULT_SIZE = 57;
	private static @Nullable CrosshairDrawing shared;
	private boolean[][] pixels = new boolean[DEFAULT_SIZE][DEFAULT_SIZE];
	private int revision;

	/** The game's drawing, read from {@link #file} the first time it's asked for. Render thread. */
	public static CrosshairDrawing shared() {
		CrosshairDrawing d = shared;
		if (d == null) {
			d = new CrosshairDrawing();
			try {
				d.load(file());
			} catch (RuntimeException | LinkageError e) {
				// No config folder (a test): an empty drawing.
			}
			shared = d;
		}
		return d;
	}

	/** Where the drawing is kept: {@code config/flintclient/crosshair-drawn.png} in the game folder. */
	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("flintclient").resolve("crosshair-drawn.png");
	}

	/** Writes the game's drawing to {@link #file} (a failure is logged; the drawing stays as drawn). */
	public static void saveShared() {
		CrosshairDrawing d = shared;
		if (d == null) {
			return;
		}
		try {
			d.save(file());
		} catch (IOException | RuntimeException e) {
			FlintLog.LOGGER.warn("Flint couldn't save the drawn crosshair", e);
		}
	}

	public int width() {
		return pixels.length;
	}

	public int height() {
		return pixels.length == 0 ? 0 : pixels[0].length;
	}

	public boolean at(int x, int y) {
		return x >= 0 && y >= 0 && x < width() && y < height() && pixels[x][y];
	}

	public void toggle(int x, int y) {
		if (x >= 0 && y >= 0 && x < width() && y < height()) {
			pixels[x][y] = !pixels[x][y];
			revision++;
		}
	}

	/** Sets pixel ({@code x}, {@code y}) on or off; whether it changed. */
	public boolean set(int x, int y, boolean on) {
		if (x < 0 || y < 0 || x >= width() || y >= height() || pixels[x][y] == on) {
			return false;
		}
		pixels[x][y] = on;
		revision++;
		return true;
	}

	/** Sets every pixel on the straight line from ({@code x0}, {@code y0}) to ({@code x1}, {@code y1}) (a fast stroke leaves no gaps). */
	public boolean line(int x0, int y0, int x1, int y1, boolean on) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		boolean changed = false;
		for (int i = 0; i <= steps; i++) {
			int x = steps == 0 ? x0 : x0 + Math.round((x1 - x0) * i / (float) steps);
			int y = steps == 0 ? y0 : y0 + Math.round((y1 - y0) * i / (float) steps);
			changed |= set(x, y, on);
		}
		return changed;
	}

	/**
	 * The same with a square brush {@code size} pixels across (1: one pixel; 2 and 3 grow it round the pointer's pixel, 2
	 * towards the right and down): every pixel it covers along the line.
	 */
	public boolean line(int x0, int y0, int x1, int y1, boolean on, int size) {
		if (size <= 1) {
			return line(x0, y0, x1, y1, on);
		}
		int from = -(size - 1) / 2;
		int to = from + size - 1;
		boolean changed = false;
		for (int dy = from; dy <= to; dy++) {
			for (int dx = from; dx <= to; dx++) {
				changed |= line(x0 + dx, y0 + dy, x1 + dx, y1 + dy, on);
			}
		}
		return changed;
	}

	/** How many pixels are on. */
	public int count() {
		int n = 0;
		for (boolean[] column : pixels) {
			for (boolean on : column) {
				n += on ? 1 : 0;
			}
		}
		return n;
	}

	/** Clears every pixel, keeping the size. */
	public void clear() {
		pixels = new boolean[width()][height()];
		revision++;
	}

	/** Changes each time a pixel does. */
	public int revision() {
		return revision;
	}

	/** Reads {@code file}; a missing or unreadable one leaves an empty grid. */
	public void load(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return;
			}
			BufferedImage image = ImageIO.read(file.toFile());
			if (image == null) {
				return;
			}
			boolean[][] read = new boolean[image.getWidth()][image.getHeight()];
			for (int x = 0; x < image.getWidth(); x++) {
				for (int y = 0; y < image.getHeight(); y++) {
					read[x][y] = Math.abs(image.getRGB(x, y)) > 1;
				}
			}
			pixels = read;
			revision++;
		} catch (IOException | RuntimeException e) {
			// An unreadable drawing is an empty one.
		}
	}

	/** Writes the drawing to {@code file}. */
	public void save(Path file) throws IOException {
		BufferedImage image = new BufferedImage(width(), height(), BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < width(); x++) {
			for (int y = 0; y < height(); y++) {
				if (pixels[x][y]) {
					image.setRGB(x, y, 0xFF000000);
				}
			}
		}
		Files.createDirectories(file.getParent());
		ImageIO.write(image, "PNG", file.toFile());
	}
}

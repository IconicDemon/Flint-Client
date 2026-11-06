/*
 * Adapted from Custom Crosshair Mod by wjbaker (CC0 1.0; META-INF/licenses/CC0-1.0-CustomCrosshairMod.txt): its drawn
 * crosshair, a grid of pixels kept as a PNG.
 */
package com.flintclient.client.control.feature;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Crosshair's Drawn style: a square grid of pixels, on or off (57 by 57 to start, as Custom Crosshair Mod's). Kept as a PNG
 * whose drawn pixels are opaque black and the rest clear; read back, a pixel is on unless it's clear or opaque white (the
 * same rule, so its {@code crosshair_drawn.ccmpng} reads the same). Pure Java apart from the image file.
 */
public final class CrosshairDrawing {
	public static final int DEFAULT_SIZE = 57;
	private boolean[][] pixels = new boolean[DEFAULT_SIZE][DEFAULT_SIZE];
	private int revision;

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

package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Rasterises every icon exactly as the mask atlas does and checks it stays clear of its cell's edge, then
 * writes a contact sheet ({@code build/icon-sheet/}) for reviewing them by eye: each icon at 12, 16, 24
 * and 48 px, the small sizes also enlarged with square pixels so their legibility can be judged. AWT runs
 * headless (no Dock icon on macOS); without usable fonts (a bare CI machine) only the sheet is skipped.
 */
class IconSheetTest {
	private static final int[] SIZES = {12, 16, 24, 48};
	private static final int BACKGROUND = 0xFF141312;
	private static final int CARD = 0xFF2A2724;
	private static final int INK = 0xFFF3ECE2;
	private static final int ACCENT = 0xFFD97706;

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** Whether AWT can measure text here (it needs fontconfig on Linux). */
	private static boolean textAvailable() {
		try {
			BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = probe.createGraphics();
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			g.getFontMetrics().stringWidth("GEAR");
			g.dispose();
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	@Test
	void everyIconHasInkAndLeavesItsOuterPixelsClear() {
		for (Icon icon : Icon.values()) {
			checkIcon(icon.name(), MaskAtlas.iconArgb(icon, 24), 24);
		}
		for (FilledIcon icon : FilledIcon.values()) {
			checkIcon("filled " + icon.name(), MaskAtlas.filledArgb(icon, 24), 24);
		}
	}

	private static void checkIcon(String name, int[] argb, int size) {
		int ink = 0;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int alpha = argb[y * size + x] >>> 24;
				ink += alpha;
				if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
					assertEquals(0, alpha, name + " touches the edge of its cell at " + x + "," + y);
				}
			}
		}
		assertTrue(ink > 255 * 8, name + " has almost no ink");
	}

	@Test
	void theFilledStarCoversTheOutlineStar() {
		int size = 48;
		int[] outline = MaskAtlas.iconArgb(Icon.STAR, size);
		int[] filled = MaskAtlas.filledArgb(FilledIcon.STAR, size);
		for (int i = 0; i < outline.length; i++) {
			// Wherever the outline has full ink, the solid star does too: they swap in place.
			if (outline[i] >>> 24 == 255) {
				assertEquals(255, filled[i] >>> 24, "pixel " + i);
			}
		}
	}

	@Test
	void writesTheContactSheet() throws IOException {
		assumeTrue(textAvailable(), "AWT has no fonts here; the contact sheet is for reviewing by eye");
		int count = Icon.values().length + FilledIcon.values().length;
		int columns = 2;
		int rows = (count + columns - 1) / columns;
		int rowH = 74;
		int colW = 620;
		BufferedImage sheet = new BufferedImage(columns * colW, rows * rowH + 8, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(BACKGROUND, true));
		g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		int index = 0;
		for (Icon icon : Icon.values()) {
			drawRow(sheet, g, icon.name(), size -> MaskAtlas.iconArgb(icon, size), index++, rows, colW, rowH);
		}
		for (FilledIcon icon : FilledIcon.values()) {
			drawRow(sheet, g, "FILLED " + icon.name(), size -> MaskAtlas.filledArgb(icon, size), index++, rows, colW, rowH);
		}
		g.dispose();
		Path out = Path.of("build", "icon-sheet", "icons.png");
		Files.createDirectories(out.getParent());
		ImageIO.write(sheet, "png", out.toFile());
		assertTrue(Files.size(out) > 0);
	}

	private interface Raster {
		int[] pixels(int size);
	}

	private static void drawRow(BufferedImage sheet, Graphics2D g, String name, Raster raster, int index, int rows, int colW, int rowH) {
		int left = (index / rows) * colW + 8;
		int top = (index % rows) * rowH + 8;
		g.setColor(new Color(0xFF8E8A83, true));
		g.drawString(name, left, top + 10);
		int x = left;
		int y = top + 16;
		// Enlarged with square pixels: 12 px ×4, 16 px ×3, 24 px ×2.
		x = blit(sheet, raster.pixels(12), 12, x, y, 4, INK, CARD) + 8;
		x = blit(sheet, raster.pixels(16), 16, x, y, 3, INK, CARD) + 8;
		x = blit(sheet, raster.pixels(24), 24, x, y, 2, INK, CARD) + 12;
		// Actual size, in the text colour and the accent.
		for (int size : SIZES) {
			x = blit(sheet, raster.pixels(size), size, x, y, 1, INK, BACKGROUND) + 6;
		}
		x += 6;
		blit(sheet, raster.pixels(16), 16, x, y, 1, ACCENT, BACKGROUND);
		blit(sheet, raster.pixels(16), 16, x + 22, y, 1, Palette.ACCENT_INK, ACCENT);
	}

	/** Composites a white-coverage mask tinted {@code ink} over {@code background}; returns the right edge. */
	private static int blit(BufferedImage sheet, int[] argb, int size, int x, int y, int zoom, int ink, int background) {
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				float a = (argb[py * size + px] >>> 24) / 255.0F;
				int rgb = Palette.lerp(background, ink | 0xFF000000, a) | 0xFF000000;
				for (int zy = 0; zy < zoom; zy++) {
					for (int zx = 0; zx < zoom; zx++) {
						sheet.setRGB(x + px * zoom + zx, y + py * zoom + zy, rgb);
					}
				}
			}
		}
		return x + size * zoom;
	}
}

package com.flintclient.client.platform;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;

/**
 * Font Awesome 6 Free Solid glyphs (SIL OFL 1.1, {@code assets/flintclient/font/fa-solid-900.ttf}) rasterised into square
 * white masks for the mask atlas ({@code render.MaskAtlas}), on Minecraft 1.8.9, where LWJGL 2 has no stb_truetype: Java's
 * own font engine draws them, placed as the 26.x rasteriser places them (one em, the glyph centred across and on Font
 * Awesome's baseline, its edges antialiased). The font is read once for every size. Platform class (see {@link Clock}).
 * Render thread.
 */
public final class GlyphRaster {
	private static final String FILE = "/assets/flintclient/font/fa-solid-900.ttf";
	/** Font Awesome's em is 512 units, 448 of them above the baseline. */
	private static final float ASCENT = 448 / 512.0F;
	private static @Nullable Font font;
	private static boolean failed;

	private GlyphRaster() {
	}

	private static @Nullable Font font() {
		if (font == null && !failed) {
			try (InputStream in = GlyphRaster.class.getResourceAsStream(FILE)) {
				if (in == null) {
					throw new java.io.IOException(FILE + " is missing");
				}
				font = Font.createFont(Font.TRUETYPE_FONT, in);
			} catch (Exception e) {
				failed = true;
				LogManager.getLogger("Flint").warn("Flint: the menu's icon font couldn't be loaded; icons are left out", e);
			}
		}
		return font;
	}

	/** {@code codepoint} as {@code size} × {@code size} ARGB pixels (white, the glyph's coverage as alpha); all clear if it can't be drawn. */
	public static int[] pixels(int codepoint, int size) {
		int[] argb = new int[size * size];
		Font base = font();
		if (base == null || size <= 0 || !base.canDisplay(codepoint)) {
			return argb;
		}
		Font f = base.deriveFont((float) size);
		FontRenderContext frc = new FontRenderContext(null, true, true);
		GlyphVector gv = f.createGlyphVector(frc, new String(Character.toChars(codepoint)));
		Rectangle box = gv.getPixelBounds(frc, 0, 0);
		if (box.width <= 0 || box.height <= 0) {
			return argb;
		}
		// The glyph's box centred across the em, its top as far below the em's top as the box's top is above the baseline.
		int left = (size - box.width) / 2;
		int top = Math.round(ASCENT * size) + box.y;
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setColor(java.awt.Color.WHITE);
		g.drawGlyphVector(gv, left - box.x, top - box.y);
		g.dispose();
		image.getRGB(0, 0, size, size, argb, 0, size);
		for (int i = 0; i < argb.length; i++) {
			argb[i] = (argb[i] >>> 24) == 0 ? 0 : (argb[i] & 0xFF000000) | 0xFFFFFF;
		}
		return argb;
	}
}

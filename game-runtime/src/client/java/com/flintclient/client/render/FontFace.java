package com.flintclient.client.render;

import com.flintclient.client.platform.FontRef;

/**
 * A typeface at one size that Flint can draw crisply: it names a font rasterised for each pixel scale
 * (see {@link FlintFonts}). The fixed text roles ({@link FlintFonts}) and free sizes for the HUD
 * ({@link ScaledFont}) both implement it, so {@link TextLine} and {@link DigitRun} take either.
 */
public interface FontFace {
	/** The font to draw with at {@code scale} pixels per unit. Render thread. */
	FontRef forScale(int scale);

	/** Em size in units (GUI units for Minecraft's own scale). */
	float size();

	/** Cap height in pixels at {@code scale} pixels per unit. */
	float capHeightPx(int scale);

	/** True when the bundled font has every character; other text is drawn in Minecraft's font. */
	boolean covers(String text);

	/**
	 * Screen pixels per font unit when drawing at {@code scale} pixels per GUI unit: the scale itself for a font rasterised
	 * for it, a whole number for Minecraft's bitmap font (whose units are its pixels) so it stays crisp.
	 */
	default int drawScale(int scale) {
		return scale;
	}
}

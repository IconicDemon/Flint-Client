package com.flintclient.client.render;

import net.minecraft.network.chat.FontDescription;

/**
 * A typeface at one size that Flint can draw crisply: it names a font rasterised for each pixel scale
 * (see {@link FlintFonts}). The fixed text roles ({@link FlintFonts}) and free sizes for the HUD
 * ({@link ScaledFont}) both implement it, so {@link TextLine} and {@link DigitRun} take either.
 */
public interface FontFace {
	/** The font to draw with at {@code scale} pixels per unit. Render thread. */
	FontDescription forScale(int scale);

	/** Em size in units (GUI units for Minecraft's own scale). */
	float size();

	/** Cap height in pixels at {@code scale} pixels per unit. */
	float capHeightPx(int scale);

	/** True when the bundled font has every character; other text is drawn in Minecraft's font. */
	boolean covers(String text);
}

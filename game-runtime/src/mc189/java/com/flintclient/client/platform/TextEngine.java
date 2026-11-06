package com.flintclient.client.platform;

import net.minecraft.client.render.TextRenderer;

/**
 * Turns text into runs the {@link Frame} draws, and measures them, on Minecraft 1.8.9: Minecraft's own font renderer and
 * glyph widths (so a resource pack's font applies). {@code render.TextLine} prepares its runs once and caches them, so drawing
 * allocates nothing. Platform class (see {@link Clock}). Render thread.
 */
public final class TextEngine {
	/** The characters on the font's ASCII page ({@code textures/font/ascii.png}), in the order of its 16 × 16 grid. */
	static final String PAGE = "ÀÁÂÈÊËÍÓÔÕÚßãõğİıŒœŞşŴŵžȇ\u0000\u0000\u0000\u0000\u0000\u0000\u0000 !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~\u0000ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜø£Ø×ƒáíóúñÑªº¿®¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αβΓπΣσμτΦΘΩδ∞∅∈∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u0000";
	/** A character's cell on the page, or -1. */
	static final short[] INDEX = new short[65536];
	private static TextEngine cached;

	static {
		java.util.Arrays.fill(INDEX, (short) -1);
		for (int i = 0; i < PAGE.length(); i++) {
			char c = PAGE.charAt(i);
			if (c != 0 && INDEX[c] == -1) {
				INDEX[c] = (short) i;
			}
		}
	}

	final TextRenderer font;

	private TextEngine(TextRenderer font) {
		this.font = font;
	}

	/** The engine for {@code font}; the same object while the font is the same (a resource reload makes a new one). */
	public static TextEngine of(TextRenderer font) {
		TextEngine engine = cached;
		if (engine == null || engine.font != font) {
			engine = new TextEngine(font);
			cached = engine;
		}
		return engine;
	}

	/** {@code text} in {@code face} (always Minecraft's font here), ready to measure and draw. */
	public TextRun run(String text, FontRef face) {
		boolean simple = true;
		for (int i = 0; i < text.length() && simple; i++) {
			char c = text.charAt(i);
			simple = c == ' ' || INDEX[c] >= 0;
		}
		return new TextRun(text, simple);
	}

	/** A run's advance in font units (Minecraft's own measure, each glyph's width and its one-unit gap). */
	public float width(TextRun run) {
		return font.getWidth(run.text);
	}
}

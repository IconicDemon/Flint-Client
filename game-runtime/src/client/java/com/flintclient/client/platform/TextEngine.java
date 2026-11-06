package com.flintclient.client.platform;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * Turns text into runs the {@link Frame} draws, and measures them: Minecraft's font renderer here, with Flint's bundled
 * faces registered as Minecraft fonts ({@link FontRef}). {@code render.TextLine} and {@code render.DigitRun} prepare their
 * runs once and cache them, so drawing allocates nothing. Platform class (see {@link Clock}). Render thread.
 */
public final class TextEngine {
	private static TextEngine cached;

	private final Font font;

	private TextEngine(Font font) {
		this.font = font;
	}

	/** The engine for {@code font} (Minecraft's, or a test's); the same object while the font is the same. */
	public static TextEngine of(Font font) {
		TextEngine engine = cached;
		if (engine == null || engine.font != font) {
			engine = new TextEngine(font);
			cached = engine;
		}
		return engine;
	}

	/** {@code text} in {@code face}, ready to measure and draw. */
	public TextRun run(String text, FontRef face) {
		return new TextRun(Component.literal(text).withStyle(face.style()));
	}

	/** A run's advance in font units. */
	public float width(TextRun run) {
		return font.getSplitter().stringWidth(run.component());
	}
}

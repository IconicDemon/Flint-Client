package com.flintclient.client.platform;

/**
 * A piece of text in Minecraft's font, prepared by a {@link TextEngine} for measuring and drawing ({@link Frame#text}), on
 * Minecraft 1.8.9. Platform class (see {@link Clock}).
 */
public final class TextRun {
	final String text;
	/** Every character is on the font's ASCII page (drawn by Flint's own batched quads); otherwise Minecraft draws it. */
	final boolean simple;

	TextRun(String text, boolean simple) {
		this.text = text;
		this.simple = simple;
	}

	public String text() {
		return text;
	}
}

package com.flintclient.client.platform;

/**
 * A font as the engine names it, on Minecraft 1.8.9: always Minecraft's own bitmap font. 1.8.9's font renderer can't draw
 * TrueType faces, and Flint's screens show every word in Minecraft's font anyway, so a bundled face is drawn in it too, at the
 * scale its size asks for. Platform class (see {@link Clock}).
 */
public final class FontRef {
	/** Minecraft's own (bitmap) font. */
	public static final FontRef MINECRAFT = new FontRef();

	private FontRef() {
	}

	/** A bundled face: Minecraft's font here (see the class comment). */
	public static FontRef bundled(String name, String file, float size, int oversample) {
		return MINECRAFT;
	}
}

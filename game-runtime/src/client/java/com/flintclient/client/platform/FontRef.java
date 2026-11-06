package com.flintclient.client.platform;

import com.flintclient.client.FlintClient;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * A font as the engine names it: Minecraft's own ({@link #MINECRAFT}), or one of Flint's bundled TrueType faces rasterised
 * for one pixel scale ({@link #bundled}). {@code render.FontFace} hands these out per scale. Platform class (see {@link Clock}).
 */
public final class FontRef {
	/** Minecraft's own (bitmap) font. */
	public static final FontRef MINECRAFT = new FontRef(FontDescription.DEFAULT);

	private final FontDescription description;
	private final Style style;

	private FontRef(FontDescription description) {
		this.description = description;
		this.style = Style.EMPTY.withFont(description);
	}

	/**
	 * A bundled face: {@code file} (a TrueType file in Flint's assets) at {@code size} GUI units, rasterised for
	 * {@code oversample} pixels per unit, named {@code flintclient:<name>}. Its font set is made the first time it is drawn
	 * ({@link FontSets}). Render thread.
	 */
	public static FontRef bundled(String name, String file, float size, int oversample) {
		Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, name);
		FontSets.register(id, new FontSets.Spec(file, size, oversample));
		return new FontRef(new FontDescription.Resource(id));
	}

	/** Minecraft's own font description, for code on this engine's side of the platform classes. */
	public FontDescription description() {
		return description;
	}

	Style style() {
		return style;
	}
}

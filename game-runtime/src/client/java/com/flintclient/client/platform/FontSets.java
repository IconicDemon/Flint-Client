package com.flintclient.client.platform;

import com.flintclient.client.FlintClient;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * The font sets behind Flint's bundled faces ({@link FontRef#bundled}). Minecraft draws TrueType glyphs with nearest
 * filtering, so text is only crisp when a font is rasterised at exactly the scale it is drawn at (its "oversample"): each
 * face therefore gets a font per scale, and the font set is created the first time it is drawn ({@code FontManagerMixin}),
 * so only the scale in use costs anything. With the {@code fontPreload} module its file and glyphs are read ahead on a worker
 * ({@code render.FontPreload}). Engines without TrueType fonts in their font manager (1.8.9) have no such class.
 */
public final class FontSets {
	/** Behind Nunito Sans in each font set, for the glyphs it lacks (arrows). */
	static final String FALLBACK = "inter-semibold.ttf";

	/**
	 * How a font set is built: the bundled TrueType file at the face's size, rasterised for one scale, with Inter behind a
	 * Nunito Sans face for the glyphs it lacks.
	 */
	public record Spec(String file, float size, int oversample) {
		public GlyphProvider load(ResourceManager resources) throws IOException {
			return load(resources, file);
		}

		/** The face behind this one in its font set, or null for none. */
		public @Nullable GlyphProvider loadFallback(ResourceManager resources) throws IOException {
			return file.startsWith("nunitosans") ? load(resources, FALLBACK) : null;
		}

		private GlyphProvider load(ResourceManager resources, String path) throws IOException {
			TrueTypeGlyphProviderDefinition definition = new TrueTypeGlyphProviderDefinition(
				Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, path), size, oversample, TrueTypeGlyphProviderDefinition.Shift.NONE, "");
			return definition.unpack().left().orElseThrow().load(resources);
		}

		/**
		 * {@link #load}, then every glyph's outline loaded, as FontManager's reload does for every font on its
		 * worker threads ({@code finalizeProviderLoading}, which also leaves out the space). Font set creation
		 * then finds them loaded. Safe on any thread; see {@code render.FontPreload} for why.
		 */
		public GlyphProvider loadWithGlyphs(ResourceManager resources) throws IOException {
			GlyphProvider provider = load(resources);
			try {
				for (IntIterator codepoints = provider.getSupportedGlyphs().iterator(); codepoints.hasNext(); ) {
					int codepoint = codepoints.nextInt();
					if (codepoint != ' ') {
						provider.getGlyph(codepoint);
					}
				}
				return provider;
			} catch (Throwable e) {
				// Anything at all (an Error too): nobody else will ever close this face.
				provider.close();
				throw e;
			}
		}
	}

	private static final Map<Identifier, Spec> SPECS = new HashMap<>();

	private FontSets() {
	}

	/** The font set to create for a Flint font name, or null if it isn't one. */
	public static @Nullable Spec spec(Identifier name) {
		synchronized (SPECS) {
			return SPECS.get(name);
		}
	}

	/** Adds a font name {@link #spec} resolves. */
	static void register(Identifier name, Spec spec) {
		synchronized (SPECS) {
			SPECS.put(name, spec);
		}
	}
}

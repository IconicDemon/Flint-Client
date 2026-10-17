package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * Flint's text roles. Minecraft draws TrueType glyphs with nearest filtering, so text is only crisp when
 * a font is rasterised at exactly the GUI scale (its "oversample"). Each role therefore gets a font per
 * GUI scale, named {@code flintclient:<role>_<scale>}; the font set is created the first time it is
 * drawn (see {@code FontManagerMixin}), so only the scale in use costs anything. With the
 * {@code fontPreload} module its file and glyphs are read ahead on a worker ({@link FontPreload}).
 */
public enum FlintFonts implements FontFace {
	/** Button labels and body text. */
	LABEL("label", "inter-medium.ttf", 4.6F, 0.7275F, Coverage.INTER),
	/** Emphasised labels: the username, card titles, STORE and QUIT GAME. */
	STRONG("strong", "inter-semibold.ttf", 4.6F, 0.7275F, Coverage.INTER),
	/** Secondary text: version line, card body, tooltips. */
	SMALL("small", "inter-regular.ttf", 3.6F, 0.7275F, Coverage.INTER),
	/** Small emphasised text: shortcut slots. */
	SMALL_BOLD("smallbold", "inter-semibold.ttf", 3.6F, 0.7275F, Coverage.INTER),
	/** Tiny tracked capitals: the "WHAT'S NEW" heading. */
	CAPS("caps", "inter-semibold.ttf", 2.9F, 0.7275F, Coverage.INTER),
	/** Page titles. */
	HEADING("heading", "inter-semibold.ttf", 7.0F, 0.7275F, Coverage.INTER),
	/** The FLINT wordmark (Outfit Bold). */
	WORDMARK("wordmark", "outfit-bold.ttf", 8.0F, 0.7F, Coverage.LATIN1),
	/** The Control Center's UI face (Plus Jakarta Sans), secondary text: Medium (500). */
	UI("ui", "jakarta-medium.ttf", 6.0F, 0.745F, Coverage.JAKARTA),
	/** Plus Jakarta Sans SemiBold (600): descriptions, notes, button text. */
	UI_SEMI("uisemi", "jakarta-semibold.ttf", 6.0F, 0.745F, Coverage.JAKARTA),
	/** Plus Jakarta Sans Bold (700): row labels, chips. */
	UI_BOLD("uibold", "jakarta-bold.ttf", 6.0F, 0.745F, Coverage.JAKARTA),
	/** Plus Jakarta Sans ExtraBold (800): titles, tile names, tabs, buttons, the wordmark. */
	UI_HEAVY("uiheavy", "jakarta-extrabold.ttf", 6.0F, 0.745F, Coverage.JAKARTA);

	/** Highest GUI scale with its own rasterisation (larger scales reuse it, slightly enlarged). */
	public static final int MAX_SCALE = 16;
	/** Minecraft places a glyph's baseline this many GUI units below the text position. */
	public static final float BASELINE = 7.0F;

	/** How a font set is built: the bundled TrueType file at the role's size, rasterised for one scale. */
	public record Spec(String file, float size, int oversample) {
		public GlyphProvider load(ResourceManager resources) throws IOException {
			TrueTypeGlyphProviderDefinition definition = new TrueTypeGlyphProviderDefinition(
				Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, file), size, oversample, TrueTypeGlyphProviderDefinition.Shift.NONE, "");
			return definition.unpack().left().orElseThrow().load(resources);
		}

		/**
		 * {@link #load}, then every glyph's outline loaded, as FontManager's reload does for every font on its
		 * worker threads ({@code finalizeProviderLoading}, which also leaves out the space). Font set creation
		 * then finds them loaded. Safe on any thread; see {@link FontPreload} for why.
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

	private final String id;
	private final String file;
	/** Em size in GUI units. */
	public final float size;
	/** Cap height as a fraction of the em. */
	public final float capHeight;
	private final Coverage coverage;
	private final Map<Integer, FontDescription.Resource> perScale = new HashMap<>();

	FlintFonts(String id, String file, float size, float capHeight, Coverage coverage) {
		this.id = id;
		this.file = file;
		this.size = size;
		this.capHeight = capHeight;
		this.coverage = coverage;
	}

	/** Render thread (the per-scale names are cached unsynchronised). */
	@Override
	public FontDescription forScale(int guiScale) {
		return resource(guiScale);
	}

	@Override
	public float size() {
		return size;
	}

	/** The font name {@link #forScale} gives, as FontManager is asked for it. Render thread. */
	public Identifier fontId(int guiScale) {
		return resource(guiScale).id();
	}

	private FontDescription.Resource resource(int guiScale) {
		int scale = Math.clamp(guiScale, 1, MAX_SCALE);
		return perScale.computeIfAbsent(scale, s -> {
			Identifier name = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, id + "_" + s);
			synchronized (SPECS) {
				SPECS.put(name, new Spec(file, size, s));
			}
			return new FontDescription.Resource(name);
		});
	}

	/** The font set to create for a Flint font name, or null if it isn't one. */
	public static @Nullable Spec spec(Identifier name) {
		synchronized (SPECS) {
			return SPECS.get(name);
		}
	}

	/** Adds a font name {@link #spec} resolves (for {@link ScaledFont}, whose sets FontManagerMixin creates the same way). */
	static void register(Identifier name, Spec spec) {
		synchronized (SPECS) {
			SPECS.put(name, spec);
		}
	}

	/**
	 * A role the main menu draws at its own size (prepared ahead by {@link FontPreload}); the Control Center's UI faces
	 * are only ever drawn through {@link ScaledFont} at their own sizes, so preparing them at the base size would be wasted.
	 */
	public boolean menuRole() {
		return compareTo(UI) < 0;
	}

	/** The bundled font file this role uses. */
	String file() {
		return file;
	}

	Coverage coverage() {
		return coverage;
	}

	/** Cap height in pixels at a GUI scale. */
	@Override
	public float capHeightPx(int guiScale) {
		return size * capHeight * guiScale;
	}

	/** True when every character is in the bundled font (Latin, Latin Extended-A/B, punctuation). */
	@Override
	public boolean covers(String text) {
		return text.codePoints().allMatch(coverage::covers);
	}

	/** Which characters a bundled font file has (all are subsets). */
	enum Coverage {
		INTER,
		LATIN1,
		/** Plus Jakarta Sans: Latin-1, Latin Extended-A (less the soft hyphen, ŉ and ſ) and the punctuation the UI uses. */
		JAKARTA;

		boolean covers(int cp) {
			if (this == JAKARTA) {
				return (cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0x17E && cp != 0xAD && cp != 0x149)
					|| cp == 0x2010 || (cp >= 0x2012 && cp <= 0x2015) || (cp >= 0x2018 && cp <= 0x201A) || (cp >= 0x201C && cp <= 0x201E)
					|| (cp >= 0x2020 && cp <= 0x2022) || cp == 0x2026 || cp == 0x2030
					|| cp == 0x2039 || cp == 0x203A || cp == 0x20AC || (cp >= 0x2190 && cp <= 0x2193) || cp == 0x2212;
			}
			if (this == LATIN1) {
				return (cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xFF);
			}
			return (cp >= 0x20 && cp <= 0x24F) || (cp >= 0x2C6 && cp <= 0x2DD) || (cp >= 0x2000 && cp <= 0x206F)
				|| cp == 0x20AC || cp == 0x2122 || (cp >= 0x2190 && cp <= 0x2193) || cp == 0x2212;
		}
	}
}

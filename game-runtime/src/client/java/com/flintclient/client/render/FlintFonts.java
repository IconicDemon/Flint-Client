package com.flintclient.client.render;

import com.flintclient.client.platform.FontRef;
import java.util.HashMap;
import java.util.Map;

/**
 * Flint's text roles. Minecraft draws TrueType glyphs with nearest filtering, so text is only crisp when
 * a font is rasterised at exactly the GUI scale (its "oversample"). Each role therefore gets a font per
 * GUI scale, named {@code flintclient:<role>_<scale>} ({@link FontRef#bundled}): only the scale in use costs anything.
 */
public enum FlintFonts implements FontFace {
	/** Button labels and body text (Nunito Sans Bold, the main face of all of Flint's text but the wordmark; cap height 705 of 1000). */
	LABEL("label", "nunitosans-bold.ttf", 4.6F, 0.705F, Coverage.INTER),
	/** Emphasised labels: the username, card titles, STORE and QUIT GAME. */
	STRONG("strong", "nunitosans-extrabold.ttf", 4.6F, 0.705F, Coverage.INTER),
	/** Secondary text: version line, card body, tooltips. */
	SMALL("small", "nunitosans-bold.ttf", 3.6F, 0.705F, Coverage.INTER),
	/** Small emphasised text: shortcut slots. */
	SMALL_BOLD("smallbold", "nunitosans-extrabold.ttf", 3.6F, 0.705F, Coverage.INTER),
	/** Tiny tracked capitals: the "WHAT'S NEW" heading. */
	CAPS("caps", "nunitosans-extrabold.ttf", 2.9F, 0.705F, Coverage.INTER),
	/** Page titles. */
	HEADING("heading", "nunitosans-black.ttf", 7.0F, 0.705F, Coverage.INTER),
	/** The FLINT wordmark (Outfit Bold). */
	WORDMARK("wordmark", "outfit-bold.ttf", 8.0F, 0.7F, Coverage.LATIN1),
	/** The Right Shift menu's quiet text: Nunito Sans Bold (700), for notes and captions. */
	UI("ui", "nunitosans-bold.ttf", 6.0F, 0.705F, Coverage.INTER),
	/** Nunito Sans Bold (700): the menu's body text, names, descriptions. */
	UI_SEMI("uisemi", "nunitosans-bold.ttf", 6.0F, 0.705F, Coverage.INTER),
	/** Nunito Sans ExtraBold (800): setting labels, chips, buttons. */
	UI_BOLD("uibold", "nunitosans-extrabold.ttf", 6.0F, 0.705F, Coverage.INTER),
	/** Nunito Sans Black (900): titles, tabs, the header. */
	UI_HEAVY("uiheavy", "nunitosans-black.ttf", 6.0F, 0.705F, Coverage.INTER),
	/** Nunito Sans Black (900): section titles and the tabs' names. */
	UI_BLACK("uiblack", "nunitosans-black.ttf", 6.0F, 0.705F, Coverage.INTER);

	/** Highest GUI scale with its own rasterisation (larger scales reuse it, slightly enlarged). */
	public static final int MAX_SCALE = 16;
	/** Minecraft places a glyph's baseline this many GUI units below the text position. */
	public static final float BASELINE = 7.0F;

	private final String id;
	private final String file;
	/** Em size in GUI units. */
	public final float size;
	/** Cap height as a fraction of the em. */
	public final float capHeight;
	private final Coverage coverage;
	private final Map<Integer, FontRef> perScale = new HashMap<>();

	FlintFonts(String id, String file, float size, float capHeight, Coverage coverage) {
		this.id = id;
		this.file = file;
		this.size = size;
		this.capHeight = capHeight;
		this.coverage = coverage;
	}

	/** Render thread (the per-scale names are cached unsynchronised). */
	@Override
	public FontRef forScale(int guiScale) {
		int scale = Math.clamp(guiScale, 1, MAX_SCALE);
		return perScale.computeIfAbsent(scale, s -> FontRef.bundled(id + "_" + s, file, size, s));
	}

	@Override
	public float size() {
		return size;
	}

	/**
	 * A role the main menu draws at its own size (prepared ahead by {@code FontPreload} where the engine has one); the Control Center's UI faces
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
		LATIN1;

		boolean covers(int cp) {
			if (this == LATIN1) {
				return (cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xFF);
			}
			return (cp >= 0x20 && cp <= 0x24F) || (cp >= 0x2C6 && cp <= 0x2DD) || (cp >= 0x2000 && cp <= 0x206F)
				|| cp == 0x20AC || cp == 0x2122 || (cp >= 0x2190 && cp <= 0x2193) || cp == 0x2212;
		}
	}
}

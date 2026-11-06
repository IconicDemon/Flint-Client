package com.flintclient.client.control.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Right Shift menu's theme: one of the Flint app's own looks (Ember, Flint's own, then the launcher's themes, with its colours)
 * and the menu's font. Saved in Flint's settings (the same in every profile); the menu derives every colour it draws from
 * the look (see {@code MenuStyle}). Version-neutral; immutable.
 *
 * @param look the colours: Flint (Dawn's slate black, white and orange, Flint's own), or one of the launcher's themes
 * @param font Flint's typeface, or Minecraft's own font
 */
public record MenuTheme(Look look, Font font) {
	/** The launcher's themes, colour for colour (app/src/styles/tokens.css). */
	public enum Look implements Labelled {
		/**
		 * Shown as "Ember". Its id stays {@code flint} so saved choices keep it; the older, near-identical Ember look was
		 * folded into it ({@link #byId} reads {@code ember} as this one).
		 */
		FLINT("Ember", "Slate black, white and orange", 0x0D0F12, 0x0A0C0E, 0x0F1215, 0x171B20, 0x1E2329, 0x2C3033, 0xEDEDED, 0xF97316, 0xFA8A3C,
			0xDB6410, 0xFFFFFF, 0xFB923C),
		OBSIDIAN("Obsidian", "Graphite, neutral", 0x080809, 0x040405, 0x0F0F11, 0x151517, 0x1B1B1E, 0x242427, 0xD6D7DC, 0xA8ABB6, 0xB8BBC4, 0x8F929C,
			0x0C0C0E, 0xC4C7CF),
		MOSS("Moss", "Forest and sage", 0x0B0C0B, 0x060706, 0x121412, 0x171A17, 0x1D211D, 0x262B26, 0xE4EAE0, 0x76986A, 0x87A979, 0x61815A, 0x0B130A,
			0xA5C196),
		BLOODSTONE("Bloodstone", "Deep crimson", 0x0D0A0A, 0x070505, 0x141010, 0x1A1515, 0x211B1B, 0x2B2323, 0xF0E5E3, 0xAC383E, 0xBF4A50, 0x93303A,
			0xFFF4F2, 0xDC7F82),
		DUSK("Dusk", "Plum violet", 0x0B0A0E, 0x060509, 0x131119, 0x19161F, 0x1F1B27, 0x292432, 0xE8E4F0, 0x9276BA, 0xA287C7, 0x7A619F, 0x120D1C,
			0xB9A3DC);

		/** The id of the Ember look that was removed (it matched Flint's own); read as {@link #FLINT}. */
		private static final String REMOVED_EMBER = "ember";

		private final String label;
		private final String note;
		/** The page behind everything, and the darkest rail. */
		public final int background;
		public final int rail;
		/** Four raised surfaces, darkest first. */
		public final int surface1;
		public final int surface2;
		public final int surface3;
		public final int surface4;
		/** Text. */
		public final int ink;
		/** The accent, with its hover and pressed shades, the text drawn on it, and its legible tint for text. */
		public final int accent;
		public final int accentHover;
		public final int accentPress;
		public final int accentInk;
		public final int accentText;

		Look(String label, String note, int background, int rail, int surface1, int surface2, int surface3, int surface4, int ink, int accent,
			int accentHover, int accentPress, int accentInk, int accentText) {
			this.label = label;
			this.note = note;
			this.background = background;
			this.rail = rail;
			this.surface1 = surface1;
			this.surface2 = surface2;
			this.surface3 = surface3;
			this.surface4 = surface4;
			this.ink = ink;
			this.accent = accent;
			this.accentHover = accentHover;
			this.accentPress = accentPress;
			this.accentInk = accentInk;
			this.accentText = accentText;
		}

		@Override
		public String label() {
			return label;
		}

		/** A few words on it, under its name on the Theme page. */
		public String note() {
			return note;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Look byId(@Nullable String id) {
			if (REMOVED_EMBER.equals(id)) {
				return FLINT;
			}
			for (Look l : values()) {
				if (l.id().equals(id)) {
					return l;
				}
			}
			return null;
		}
	}

	/**
	 * The menu's typeface as saved by earlier versions: Flint's own (Nunito Sans) or Minecraft's pixel font. Kept so
	 * saved settings still read; the menu now always draws in Minecraft's font.
	 */
	public enum Font implements Labelled {
		DEFAULT("Default"),
		MINECRAFT("Minecraft");

		private final String label;

		Font(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Font byId(@Nullable String id) {
			for (Font f : values()) {
				if (f.id().equals(id)) {
					return f;
				}
			}
			return null;
		}
	}

	/** Flint's own look: Dawn's slate black, white and orange. */
	public static final MenuTheme FLINT = new MenuTheme(Look.FLINT, Font.DEFAULT);

	public MenuTheme {
		if (look == null) {
			look = Look.FLINT;
		}
		if (font == null) {
			font = Font.DEFAULT;
		}
	}

	public MenuTheme withLook(Look value) {
		return new MenuTheme(value, font);
	}

	public MenuTheme withFont(Font value) {
		return new MenuTheme(look, value);
	}

	/** The accent ({@code 0xRRGGBB}). */
	public int accentRgb() {
		return look.accent;
	}

	/** A theme read from its saved parts; anything missing or unknown falls back to Flint's own. */
	public static MenuTheme read(@Nullable String lookId, @Nullable String fontId) {
		Look look = Look.byId(lookId);
		Font font = Font.byId(fontId);
		return new MenuTheme(look == null ? FLINT.look : look, font == null ? FLINT.font : font);
	}
}

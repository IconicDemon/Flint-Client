package com.flintclient.client.control.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Right Shift menu's theme: one of the Flint app's own looks (Orange, Flint's own, then the launcher's themes, with its colours)
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
		 * Shown as "Orange" (it was "Ember"). Its id stays {@code flint} so saved choices keep it; the older, near-identical Ember
		 * look was folded into it ({@link #byId} reads {@code ember} as this one).
		 */
		FLINT("Orange", "Slate black, white and orange", 0x0D0F12, 0x0A0C0E, 0x0F1215, 0x171B20, 0x1E2329, 0x2C3033, 0xEDEDED, 0xF97316, 0xFA8A3C,
			0xDB6410, 0xFFFFFF, 0xFB923C),
		/**
		 * The light theme (it replaced Obsidian, whose saved id {@code obsidian} reads as it): vanilla cream, cocoa text, a strawberry
		 * accent. Its accent as text is deepened for contrast on cream.
		 */
		ICE_CREAM("Ice Cream", "Strawberry & vanilla", 0xFBF6EE, 0xF4ECDF, 0xFFFDF9, 0xF8F1E6, 0xF4ECDF, 0xE6DACB, 0x2A211C, 0xE07A93, 0xD2667F,
			0xC25570, 0x2A211C, 0xAE4159),
		/** Shown as "Mint" (saved as {@code moss}, its old name): a cool mint green on a charcoal page with a hint of green. */
		MOSS("Mint", "Cool mint on charcoal", 0x0A0E0D, 0x060908, 0x0F1513, 0x141B19, 0x1A2421, 0x24322D, 0xE4F2EC, 0x3EB489, 0x5CC6A0, 0x31986F,
			0x04150E, 0x98E2C6),
		/** Shown as "Cherry" (saved as {@code bloodstone}, its old name). */
		BLOODSTONE("Cherry", "Deep crimson", 0x0D0A0A, 0x070505, 0x141010, 0x1A1515, 0x211B1B, 0x2B2323, 0xF0E5E3, 0xAC383E, 0xBF4A50, 0x93303A,
			0xFFF4F2, 0xDC7F82),
		/** Shown as "Passion Fruit" (saved as {@code dusk}, its old name). */
		DUSK("Passion Fruit", "Plum violet", 0x0B0A0E, 0x060509, 0x131119, 0x19161F, 0x1F1B27, 0x292432, 0xE8E4F0, 0x9276BA, 0xA287C7, 0x7A619F, 0x120D1C,
			0xB9A3DC),
		/**
		 * Shown as "Blueberry" (saved as {@code aurora}, its old name): a soft blueberry accent on a deep indigo page, one solid colour
		 * (no gradient).
		 */
		AURORA("Blueberry", "Blueberry on deep indigo", 0x0B0D1C, 0x070814, 0x111427, 0x161A31, 0x1E233F, 0x283052, 0xE6E9F7, 0x6B7FE3, 0x8396EA,
			0x5568CC, 0x0A0E26, 0xA7B4F2);

		/** The id Obsidian was saved under; read as {@link #ICE_CREAM}, which replaced it. */
		private static final String REMOVED_OBSIDIAN = "obsidian";

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
			return name().toLowerCase(Locale.ROOT).replace("_", "");
		}

		/** Whether this is a light theme (dark text on a light page): the menus draw their light variants. */
		public boolean light() {
			return (((background >> 16) & 255) + ((background >> 8) & 255) + (background & 255)) > 3 * 128;
		}

		/** The name the Flint app gives this theme (its Settings › Appearance; {@code ember} for Flint's own). */
		public String launcherId() {
			return this == FLINT ? REMOVED_EMBER : id();
		}

		/**
		 * The look for a theme the Flint app names ({@code ember}, {@code icecream}, {@code moss}, {@code bloodstone}, {@code dusk}, {@code aurora},
		 * and the older names it reads as Obsidian); null for one this game doesn't know (a newer app's), which leaves the look as it is.
		 */
		public static @Nullable Look forLauncher(@Nullable String launcherId) {
			if (launcherId == null) {
				return null;
			}
			String id = launcherId.trim().toLowerCase(Locale.ROOT);
			return switch (id) {
				case "mono", "slate", "frost" -> ICE_CREAM;
				default -> byId(id);
			};
		}

		static @Nullable Look byId(@Nullable String id) {
			if (REMOVED_EMBER.equals(id)) {
				return FLINT;
			}
			if (REMOVED_OBSIDIAN.equals(id)) {
				return ICE_CREAM;
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

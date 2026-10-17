package com.flintclient.client.control.model;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Right Shift menu's theme: its accent colour, the surface family its panels are drawn in, how round its corners
 * are, and how strong its window shadow and the dim behind it are. Saved in Flint's settings (the same in every profile);
 * the menu derives every colour it draws from it (see {@code MenuStyle}). Version-neutral; immutable.
 *
 * @param accentRgb the accent ({@code 0xRRGGBB}): selected things, primary buttons, focus
 * @param surface the panels' colour family
 * @param roundness corner radius, percent of Flint's own (40..160)
 * @param dim how dark the game gets behind the window, percent (20..90)
 */
public record MenuTheme(int accentRgb, Surface surface, int roundness, int dim) {
	public static final int MIN_ROUNDNESS = 40;
	public static final int MAX_ROUNDNESS = 160;
	public static final int MIN_DIM = 20;
	public static final int MAX_DIM = 90;

	/** The panels' colour family. CHARCOAL is Flint's own warm charcoal; the others re-tint the same light levels. */
	public enum Surface implements Labelled {
		CHARCOAL("Charcoal", 1.00F, 1.00F, 1.00F, 1.00F),
		GRAPHITE("Graphite", 1.00F, 1.00F, 1.00F, 0.97F),
		MIDNIGHT("Midnight", 0.90F, 0.97F, 1.12F, 0.92F),
		ONYX("Onyx", 1.00F, 0.99F, 0.98F, 0.74F);

		private final String label;
		/** Red, green and blue weights for a grey of a given light level (1 = neutral). */
		public final float red;
		public final float green;
		public final float blue;
		/** How light the dark surfaces are, against Flint's own (text keeps its light level). */
		public final float depth;

		Surface(String label, float red, float green, float blue, float depth) {
			this.label = label;
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.depth = depth;
		}

		@Override
		public String label() {
			return label;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Surface byId(@Nullable String id) {
			for (Surface s : values()) {
				if (s.id().equals(id)) {
					return s;
				}
			}
			return null;
		}
	}

	/** A named starting point in the Themes page. */
	public record Preset(String id, String name, MenuTheme theme) {
	}

	/** Flint's own look: ember on warm charcoal. */
	public static final MenuTheme FLINT = new MenuTheme(0xFF8139, Surface.CHARCOAL, 100, 55);

	public static final List<Preset> PRESETS = List.of(
		new Preset("flint", "Flint", FLINT),
		new Preset("graphite", "Graphite", new MenuTheme(0xFF8139, Surface.GRAPHITE, 100, 55)),
		new Preset("onyx", "Onyx", new MenuTheme(0xF0782F, Surface.ONYX, 70, 65)),
		new Preset("midnight", "Midnight", new MenuTheme(0xFF9A5C, Surface.MIDNIGHT, 100, 60)),
		new Preset("ember", "Ember", new MenuTheme(0xFF6A2B, Surface.CHARCOAL, 125, 55)),
		new Preset("sand", "Sand", new MenuTheme(0xE3B46A, Surface.GRAPHITE, 100, 50)));

	public MenuTheme {
		accentRgb &= 0xFFFFFF;
		if (surface == null) {
			surface = Surface.CHARCOAL;
		}
		roundness = Math.clamp(roundness, MIN_ROUNDNESS, MAX_ROUNDNESS);
		dim = Math.clamp(dim, MIN_DIM, MAX_DIM);
	}

	public MenuTheme withAccent(int rgb) {
		return new MenuTheme(rgb, surface, roundness, dim);
	}

	public MenuTheme withSurface(Surface value) {
		return new MenuTheme(accentRgb, value, roundness, dim);
	}

	public MenuTheme withRoundness(int value) {
		return new MenuTheme(accentRgb, surface, value, dim);
	}

	public MenuTheme withDim(int value) {
		return new MenuTheme(accentRgb, surface, roundness, value);
	}

	/** The preset this theme is exactly, or null for a custom one. */
	public @Nullable Preset preset() {
		for (Preset preset : PRESETS) {
			if (preset.theme().equals(this)) {
				return preset;
			}
		}
		return null;
	}

	/**
	 * {@code base} (an ARGB colour of Flint's charcoal family) in this theme's surface family: the same light level,
	 * re-tinted; dark surfaces take the family's depth, light text keeps its level. Charcoal returns {@code base}.
	 */
	public int tint(int base) {
		if (surface == Surface.CHARCOAL) {
			return base;
		}
		int alpha = base >>> 24;
		float light = 0.299F * (base >> 16 & 0xFF) + 0.587F * (base >> 8 & 0xFF) + 0.114F * (base & 0xFF);
		float level = light * (light < 128 ? surface.depth : 1.0F);
		int r = Math.clamp(Math.round(level * surface.red), 0, 255);
		int g = Math.clamp(Math.round(level * surface.green), 0, 255);
		int b = Math.clamp(Math.round(level * surface.blue), 0, 255);
		return alpha << 24 | r << 16 | g << 8 | b;
	}

	/** {@code #RRGGBB} for files. */
	public String accentHex() {
		return String.format(Locale.ROOT, "#%06X", accentRgb);
	}

	/** A theme read from its saved parts; anything missing or unusable falls back to Flint's own. */
	public static MenuTheme read(@Nullable String accentHex, @Nullable String surfaceId, @Nullable Integer roundness, @Nullable Integer dim) {
		int accent = FLINT.accentRgb;
		if (accentHex != null) {
			int parsed = RgbSetting.parseHex(accentHex);
			if (parsed >= 0) {
				accent = parsed;
			}
		}
		Surface surface = Surface.byId(surfaceId);
		return new MenuTheme(accent, surface == null ? FLINT.surface : surface, roundness == null ? FLINT.roundness : roundness,
			dim == null ? FLINT.dim : dim);
	}
}

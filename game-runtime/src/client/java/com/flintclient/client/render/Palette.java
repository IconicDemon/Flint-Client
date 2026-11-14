package com.flintclient.client.render;

/**
 * Flint's in-game colours (ARGB), matched to the launcher's dark theme and the brand mark. The accent family follows the
 * player's theme ({@link #useAccent}, set by {@code ThemeSync}); the rest is fixed.
 */
public final class Palette {
	private Palette() {
	}

	/*
	 * The neutrals (the page, surfaces, borders, text, the danger family): dark by default; a light theme (Ice Cream) swaps in its
	 * vanilla set (useLight). Not constants, for the same reason as the accent: read them where they are drawn. Render thread.
	 */
	/** Near black with a cool charcoal undertone (vanilla cream on a light theme): the veil over the artwork and the entrance fade. */
	public static int BACKGROUND_TOP = 0xFF0A0B0E;
	public static int BACKGROUND_BOTTOM = 0xFF0E1015;

	/** Control surfaces are dark glass over the artwork (cream glass on a light theme); borders are a faint tint of the text. */
	public static int SURFACE = 0xC40F1014;
	public static int SURFACE_HOVER = 0xD8181A20;
	public static int SURFACE_PRESS = 0xE60B0C0F;
	public static int BORDER = 0x1EFFFFFF;
	public static int BORDER_HOVER = 0x38FFFFFF;
	public static int RAIL = 0x990B0C0F;
	public static int RAIL_BORDER = 0x17FFFFFF;
	/** An opaque card or panel (the title screen's pop-ups, the menu sizes panel). */
	public static int CARD = 0xF5111216;
	/** A tooltip's fill. */
	public static int TOOLTIP = 0xF2181A1F;

	public static int TEXT = 0xFFF1EEE9;
	public static int TEXT_DIM = 0xFFC9C6C0;
	public static int MUTED = 0xFF8B8E96;
	public static int FAINT = 0xFF5D6068;
	/** The colour light tints and hairlines are made from: white on the dark themes, the text (cocoa) on a light one. */
	public static int TINT = 0xFFFFFFFF;

	/** Flint orange, from the brand mark: the accent while the theme is Ember (the default). */
	public static final int EMBER_ACCENT = 0xFFD97706;
	public static final int EMBER_ACCENT_HOVER = 0xFFE58717;
	public static final int EMBER_ACCENT_PRESS = 0xFFC26A05;
	public static final int EMBER_ACCENT_INK = 0xFF1C1206;
	public static final int EMBER_ACCENT_TEXT = 0xFFE7962F;

	/*
	 * The accent: the player's theme (chosen in the Flint app, or on the Right Shift menu's Theme page), set by
	 * control/ThemeSync through useAccent. Not constants: read them where they are drawn and never copy them into a static
	 * final, so every screen, notice and control follows a change of theme at once. Render thread.
	 */
	/** The accent fill (switches, primary buttons, what is chosen). */
	public static int ACCENT = EMBER_ACCENT;
	/** The accent under the pointer, and pressed. */
	public static int ACCENT_HOVER = EMBER_ACCENT_HOVER;
	public static int ACCENT_PRESS = EMBER_ACCENT_PRESS;
	/** Text and icons drawn on an accent fill. */
	public static int ACCENT_INK = EMBER_ACCENT_INK;
	/** The accent as text or a thin mark on the dark surfaces (each theme's legible tint). */
	public static int ACCENT_TEXT = EMBER_ACCENT_TEXT;
	/** Whether the player's theme is a light one (Ice Cream): the notices and menus that follow it draw light. */
	private static boolean light;
	/** Bumped at every change of accent, so a cache of colours made from it can tell it is stale. */
	private static int accentVersion;
	private static final java.util.List<Runnable> FOLLOWERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Quit and other destructive actions: subdued dark crimson (a soft rose with deep red text on a light theme). */
	public static int DANGER_SURFACE = 0xCC241215;
	public static int DANGER_SURFACE_HOVER = 0xDD2F161A;
	public static int DANGER_BORDER = 0x40E07D70;
	public static int DANGER_BORDER_HOVER = 0x66E07D70;
	public static int DANGER_TEXT = 0xFFE07D70;
	public static int DANGER_TEXT_HOVER = 0xFFF0907F;

	/** The dark neutrals, as they always were, then the light (vanilla) ones; same order as {@link #neutrals}. */
	private static final int[] DARK = {0xFF0A0B0E, 0xFF0E1015, 0xC40F1014, 0xD8181A20, 0xE60B0C0F, 0x1EFFFFFF, 0x38FFFFFF, 0x990B0C0F, 0x17FFFFFF,
		0xF5111216, 0xF2181A1F, 0xFFF1EEE9, 0xFFC9C6C0, 0xFF8B8E96, 0xFF5D6068, 0xFFFFFFFF, 0xCC241215, 0xDD2F161A, 0x40E07D70, 0x66E07D70,
		0xFFE07D70, 0xFFF0907F};
	private static final int[] LIGHT = {0xFFFBF6EE, 0xFFF4ECDF, 0xD9FFFDF9, 0xE8FFFFFF, 0xF0F4ECDF, 0x262A211C, 0x452A211C, 0xB3F4ECDF, 0x1F2A211C,
		0xF7FFFDF9, 0xF7FFFDF9, 0xFF2A211C, 0xFF4A3F38, 0xFF6B5F57, 0xFF9A8E85, 0xFF2A211C, 0xD9FBE4E2, 0xE6F8D6D3, 0x40B8282B, 0x70B8282B,
		0xFFA82326, 0xFF8C1B1E};

	private static void neutrals(int[] v) {
		BACKGROUND_TOP = v[0];
		BACKGROUND_BOTTOM = v[1];
		SURFACE = v[2];
		SURFACE_HOVER = v[3];
		SURFACE_PRESS = v[4];
		BORDER = v[5];
		BORDER_HOVER = v[6];
		RAIL = v[7];
		RAIL_BORDER = v[8];
		CARD = v[9];
		TOOLTIP = v[10];
		TEXT = v[11];
		TEXT_DIM = v[12];
		MUTED = v[13];
		FAINT = v[14];
		TINT = v[15];
		DANGER_SURFACE = v[16];
		DANGER_SURFACE_HOVER = v[17];
		DANGER_BORDER = v[18];
		DANGER_BORDER_HOVER = v[19];
		DANGER_TEXT = v[20];
		DANGER_TEXT_HOVER = v[21];
	}

	/** {@code argb}'s alpha on {@link #TINT}: a white tint (0x14FFFFFF) on the dark themes, the same in cocoa on a light one. */
	public static int tint(int argb) {
		return (argb & 0xFF000000) | (TINT & 0xFFFFFF);
	}

	public static final int STATUS_ONLINE = 0xFF4FCB7E;
	public static final int STATUS_INCOMPATIBLE = 0xFFD9A441;
	public static final int STATUS_OFFLINE = 0xFFC9574C;
	public static final int STATUS_UNKNOWN = 0xFF4A4D55;

	/**
	 * Makes {@code accent} the accent, with its hover and pressed shades, the ink drawn on it and its tint for text (all made
	 * opaque); then tells the followers ({@link #follow}). A no-op when nothing changes.
	 */
	public static void useAccent(int accent, int hover, int press, int ink, int text) {
		int a = accent | 0xFF000000;
		int h = hover | 0xFF000000;
		int p = press | 0xFF000000;
		int i = ink | 0xFF000000;
		int t = text | 0xFF000000;
		if (a == ACCENT && h == ACCENT_HOVER && p == ACCENT_PRESS && i == ACCENT_INK && t == ACCENT_TEXT) {
			return;
		}
		ACCENT = a;
		ACCENT_HOVER = h;
		ACCENT_PRESS = p;
		ACCENT_INK = i;
		ACCENT_TEXT = t;
		accentVersion++;
		for (Runnable follower : FOLLOWERS) {
			follower.run();
		}
	}

	/**
	 * {@link #useAccent} from the accent alone (a theme that names only that): hover 12% lighter, pressed 12% darker, near-black
	 * ink on a light accent and white on a dark one, and a lighter tint for text when the accent is dark.
	 */
	public static void useAccent(int accent) {
		int a = accent | 0xFF000000;
		boolean light = luminance(a) > 0.32;
		useAccent(a, lerp(a, 0xFFFFFFFF, 0.12F), lerp(a, 0xFF000000, 0.12F), light ? 0xFF0C0C0E : 0xFFFFFFFF,
			light ? a : lerp(a, 0xFFFFFFFF, 0.25F));
	}

	/** Back to Flint's own orange (the Ember theme). */
	public static void useEmberAccent() {
		useAccent(EMBER_ACCENT, EMBER_ACCENT_HOVER, EMBER_ACCENT_PRESS, EMBER_ACCENT_INK, EMBER_ACCENT_TEXT);
	}

	/** Whether the player's theme is a light one (dark text on light panels). */
	public static boolean light() {
		return light;
	}

	/** Makes the theme light or dark (set with the accent, by {@code ThemeSync}); tells the followers when it changes. */
	public static void useLight(boolean value) {
		if (light == value) {
			return;
		}
		light = value;
		neutrals(value ? LIGHT : DARK);
		accentVersion++;
		for (Runnable follower : FOLLOWERS) {
			follower.run();
		}
	}

	/** Whether the accent is Flint's own orange (the Ember theme, the default). */
	public static boolean ember() {
		return ACCENT == EMBER_ACCENT;
	}

	/**
	 * A colour a screen draws in Flint's orange ({@code emberArgb}, its alpha included): itself on Ember, else the theme's accent
	 * at the same alpha. For the places that had their own shade of orange, so Ember looks exactly as before.
	 */
	public static int themed(int emberArgb) {
		return ember() ? emberArgb : (emberArgb & 0xFF000000) | (ACCENT & 0xFFFFFF);
	}

	/** {@link #themed} for an orange under the pointer: the theme's hover shade. */
	public static int themedHover(int emberArgb) {
		return ember() ? emberArgb : (emberArgb & 0xFF000000) | (ACCENT_HOVER & 0xFFFFFF);
	}

	/** {@link #themed} for orange text or a thin mark on a dark surface: the theme's text tint. */
	public static int themedText(int emberArgb) {
		return ember() ? emberArgb : (emberArgb & 0xFF000000) | (ACCENT_TEXT & 0xFFFFFF);
	}

	/** Changes with every change of accent (for caches of colours made from it). */
	public static int accentVersion() {
		return accentVersion;
	}

	/**
	 * Runs {@code follower} after every change of accent (render thread): for a class that keeps colours made from it
	 * ({@code Tokens}, {@code NoticeStyle}). Registered once, from that class's static initialiser.
	 */
	public static void follow(Runnable follower) {
		FOLLOWERS.add(follower);
	}

	/** Relative luminance (WCAG) of an ARGB colour, 0 to 1. */
	public static double luminance(int argb) {
		double[] c = {(argb >> 16) & 255, (argb >> 8) & 255, argb & 255};
		for (int i = 0; i < 3; i++) {
			double v = c[i] / 255;
			c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
	}

	public static int lerp(int from, int to, float t) {
		if (t <= 0) {
			return from;
		}
		if (t >= 1) {
			return to;
		}
		int a = lerpChannel(from >>> 24, to >>> 24, t);
		int r = lerpChannel((from >> 16) & 255, (to >> 16) & 255, t);
		int g = lerpChannel((from >> 8) & 255, (to >> 8) & 255, t);
		int b = lerpChannel(from & 255, to & 255, t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int lerpChannel(int from, int to, float t) {
		return Math.round(from + (to - from) * t);
	}

	/** A translucent colour composited over an opaque one: the colour actually seen there. */
	public static int over(int top, int opaqueBottom) {
		return lerp(opaqueBottom | 0xFF000000, top | 0xFF000000, (top >>> 24) / 255.0F);
	}

	public static int withAlpha(int argb, float alpha) {
		int a = Math.round((argb >>> 24) * Math.clamp(alpha, 0.0F, 1.0F));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}

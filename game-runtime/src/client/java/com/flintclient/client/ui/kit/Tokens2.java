package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Palette;

/**
 * The Control Center deck's visual tokens (colours ARGB, sizes in UI units). One accent, ember, used for
 * state and focus only; depth comes from a soft edge shadow and one hairline highlight, never from glow or
 * decorative gradients. The older {@link Tokens} stay as they are (the HUD and the main menu read them).
 * Methods rather than constants are the values that change in high contrast.
 */
public final class Tokens2 {
	private Tokens2() {
	}

	/**
	 * The deck: #121110, fully opaque. (The design called for 96%; without a blur behind it, bright HUD text and sky
	 * showed through as faint ghosts behind the deck's own text, so it is solid.)
	 */
	public static final int DECK = 0xFF121110;
	/** The deck's highlight on its leading edge and top. */
	public static final int DECK_EDGE = 0x10FFFFFF;
	/** Preview wells. */
	public static final int WELL = 0xFF0B0A09;
	public static final int HOVER = 0x08FFFFFF;
	public static final int PRESS = 0x0EFFFFFF;
	public static final int SELECTED = 0x0BFFFFFF;
	public static final int HAIRLINE = 0x0CFFFFFF;
	public static final int HAIRLINE_STRONG = 0x18FFFFFF;
	private static final int HAIRLINE_HIGH_CONTRAST = 0x2AFFFFFF;
	private static final int HAIRLINE_STRONG_HIGH_CONTRAST = 0x40FFFFFF;

	public static final int TEXT = 0xFFF2EEE8;
	public static final int TEXT_DIM = 0xFFC9C3B9;
	public static final int MUTED = 0xFF8D877E;
	private static final int MUTED_HIGH_CONTRAST = 0xFFB5AFA6;
	public static final int FAINT = 0xFF5B5650;

	/** Ember fills (switches, sliders, primary buttons, the underline). */
	public static final int EMBER = Palette.ACCENT;
	public static final int EMBER_HOVER = Palette.ACCENT_HOVER;
	public static final int EMBER_PRESS = Palette.ACCENT_PRESS;
	/** Ember for icons and text on the dark deck. */
	public static final int EMBER_TEXT = 0xFFEC9A3C;
	/** Ember at 14%: subtle fills. */
	public static final int EMBER_SOFT = Palette.withAlpha(Palette.ACCENT, 0.14F);
	/** Text on an ember fill. */
	public static final int INK = Palette.ACCENT_INK;
	public static final int WARN = 0xFFDDAA5E;
	public static final int DANGER = 0xFFE07D70;

	public static final int SWITCH_OFF_TRACK = 0xFF2A2724;
	public static final int SWITCH_ON_KNOB = 0xFF1A120A;
	public static final int SLIDER_TRACK = 0x16FFFFFF;
	public static final int KNOB_RING = 0x60000000;
	/** Text selection in fields. */
	public static final int SELECTION = Palette.withAlpha(Palette.ACCENT, 0.36F);

	// ---------- sizes (UI units) ----------

	/** The deck's padding at its sides. */
	public static final float PAD = 14;
	public static final float RADIUS_DECK = 8;
	public static final float RADIUS_ROW = 5;
	public static final float RADIUS_WELL = 6;
	/** List rows (modules, layouts). */
	public static final float ROW = 30;
	/** Setting rows. */
	public static final float SETTING_ROW = 22;
	/** Segmented tracks, fields' inner controls, primary buttons. */
	public static final float CONTROL = 16;
	/** Gap between the keyboard focus ring and its control. */
	public static final float FOCUS_GAP = 1.5F;

	public static int hairline() {
		return Kit.prefs().highContrast() ? HAIRLINE_HIGH_CONTRAST : HAIRLINE;
	}

	public static int hairlineStrong() {
		return Kit.prefs().highContrast() ? HAIRLINE_STRONG_HIGH_CONTRAST : HAIRLINE_STRONG;
	}

	public static int muted() {
		return Kit.prefs().highContrast() ? MUTED_HIGH_CONTRAST : MUTED;
	}

	/** Faint text stays readable in high contrast. */
	public static int faint() {
		return Kit.prefs().highContrast() ? MUTED : FAINT;
	}
}

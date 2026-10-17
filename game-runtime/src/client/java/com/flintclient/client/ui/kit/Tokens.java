package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Palette;

/**
 * The kit's visual tokens: warm charcoal surfaces, thin outlines and Flint's ember accent. The main
 * menu's {@link Palette} is left as it is; the accent family is shared with it. Colours are ARGB.
 * Methods (rather than constants) are the values that change in high-contrast mode.
 */
public final class Tokens {
	private Tokens() {
	}

	/** The Control Center window: #141312 at 97%. */
	public static final int WINDOW = 0xF7141312;
	public static final int RAIL = 0xFF0F0E0D;
	/** Raised cards. */
	public static final int CARD = 0xFF191816;
	public static final int CARD_HOVER = 0xFF201E1C;
	/** Selected or pressed surfaces. */
	public static final int SELECTED = 0xFF2A2724;
	/** The contextual settings panel. */
	public static final int PANEL = 0xFF171615;
	/** Recessed wells (the live preview box, the segmented control's track). */
	public static final int WELL = 0xFF0C0B0A;
	public static final int SEGMENT_TRACK = 0xFF0F0E0D;

	public static final int OUTLINE = 0x12FFFFFF;
	public static final int OUTLINE_HOVER = 0x24FFFFFF;
	public static final int OUTLINE_HIGH_CONTRAST = 0x40FFFFFF;
	public static final int OUTLINE_HOVER_HIGH_CONTRAST = 0x66FFFFFF;
	/** Secondary buttons' border. */
	public static final int BUTTON_BORDER = 0x14FFFFFF;

	public static final int TEXT = 0xFFF3ECE2;
	public static final int TEXT_DIM = 0xFFC9C3B8;
	public static final int MUTED = 0xFF8E8A83;
	public static final int MUTED_HIGH_CONTRAST = 0xFFB5B0A8;
	public static final int FAINT = 0xFF5E5A54;

	public static final int ACCENT = Palette.ACCENT;
	public static final int ACCENT_HOVER = Palette.ACCENT_HOVER;
	public static final int ACCENT_PRESS = Palette.ACCENT_PRESS;
	public static final int ACCENT_INK = Palette.ACCENT_INK;
	public static final int ACCENT_TEXT = Palette.ACCENT_TEXT;
	/** The accent at 16%: selected chips, active icon tiles. */
	public static final int ACCENT_SOFT = Palette.withAlpha(Palette.ACCENT, 0.16F);

	public static final int WARNING = 0xFFDBA85C;
	public static final int DANGER = 0xFFDE6C62;
	public static final int DANGER_HOVER = 0xFFE88177;
	public static final int DANGER_PRESS = 0xFFC95A51;
	/** Text on the danger fill. */
	public static final int DANGER_INK = 0xFF1E0D0B;
	public static final int SUCCESS = 0xFF74BA8C;

	public static final int TOGGLE_OFF_TRACK = 0xFF2A2724;
	public static final int TOGGLE_OFF_KNOB = 0xFF8B8E96;
	public static final int TOGGLE_ON_KNOB = 0xFF1C1206;
	public static final int SLIDER_TRACK = 0xFF2A2724;
	public static final int SLIDER_KNOB = 0xFFF3ECE2;
	/** Text selection in fields. */
	public static final int SELECTION = Palette.withAlpha(Palette.ACCENT, 0.38F);
	/** The modal scrim: black at 50%. */
	public static final int SCRIM = 0x80000000;
	/** The in-world dim behind a kit screen: black at 45%. */
	public static final int DIM = 0x73000000;
	/** Tooltips, matching {@code FlintTooltip}. */
	public static final int TOOLTIP_FILL = 0xF2181A1F;
	public static final int TOOLTIP_BORDER = Palette.BORDER_HOVER;

	/** Radii in units. */
	public static final float RADIUS_WINDOW = 6;
	public static final float RADIUS_CARD = 5;
	public static final float RADIUS_CONTROL = 4;
	/** Buttons and fields. */
	public static final float CONTROL_HEIGHT = 16;
	/** Gap between the focus ring and its control, in units. */
	public static final float FOCUS_GAP = 1.5F;

	public static int outline() {
		return Kit.prefs().highContrast() ? OUTLINE_HIGH_CONTRAST : OUTLINE;
	}

	public static int outlineHover() {
		return Kit.prefs().highContrast() ? OUTLINE_HOVER_HIGH_CONTRAST : OUTLINE_HOVER;
	}

	public static int muted() {
		return Kit.prefs().highContrast() ? MUTED_HIGH_CONTRAST : MUTED;
	}

	/** Faint text (notes) stays readable in high contrast. */
	public static int faint() {
		return Kit.prefs().highContrast() ? MUTED : FAINT;
	}

	public static int buttonBorder() {
		return Kit.prefs().highContrast() ? OUTLINE_HIGH_CONTRAST : BUTTON_BORDER;
	}
}

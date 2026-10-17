package com.flintclient.client.render;

/** Flint's in-game colours (ARGB), matched to the launcher's dark theme and the brand mark. */
public final class Palette {
	private Palette() {
	}

	/** Near black with a cool charcoal undertone: the veil over the artwork and the entrance fade. */
	public static final int BACKGROUND_TOP = 0xFF0A0B0E;
	public static final int BACKGROUND_BOTTOM = 0xFF0E1015;

	/** Control surfaces are dark glass over the artwork; borders are faint white so they suit any backdrop. */
	public static final int SURFACE = 0xC40F1014;
	public static final int SURFACE_HOVER = 0xD8181A20;
	public static final int SURFACE_PRESS = 0xE60B0C0F;
	public static final int BORDER = 0x1EFFFFFF;
	public static final int BORDER_HOVER = 0x38FFFFFF;
	public static final int RAIL = 0x990B0C0F;
	public static final int RAIL_BORDER = 0x17FFFFFF;

	public static final int TEXT = 0xFFF1EEE9;
	public static final int TEXT_DIM = 0xFFC9C6C0;
	public static final int MUTED = 0xFF8B8E96;
	public static final int FAINT = 0xFF5D6068;

	/** Flint orange, from the brand mark. */
	public static final int ACCENT = 0xFFD97706;
	public static final int ACCENT_HOVER = 0xFFE58717;
	public static final int ACCENT_PRESS = 0xFFC26A05;
	public static final int ACCENT_INK = 0xFF1C1206;
	public static final int ACCENT_TEXT = 0xFFE7962F;

	/** Quit and other destructive actions: subdued dark crimson. */
	public static final int DANGER_SURFACE = 0xCC241215;
	public static final int DANGER_SURFACE_HOVER = 0xDD2F161A;
	public static final int DANGER_BORDER = 0x40E07D70;
	public static final int DANGER_BORDER_HOVER = 0x66E07D70;
	public static final int DANGER_TEXT = 0xFFE07D70;
	public static final int DANGER_TEXT_HOVER = 0xFFF0907F;

	public static final int STATUS_ONLINE = 0xFF4FCB7E;
	public static final int STATUS_INCOMPATIBLE = 0xFFD9A441;
	public static final int STATUS_OFFLINE = 0xFFC9574C;
	public static final int STATUS_UNKNOWN = 0xFF4A4D55;

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

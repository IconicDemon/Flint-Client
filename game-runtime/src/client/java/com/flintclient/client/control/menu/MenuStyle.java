package com.flintclient.client.control.menu;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.ScaledFont;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.control.model.MenuTheme;

/**
 * The Right Shift menu's look, taken from the owner's reference ({@code 01-mods-menu.png}, 3200 × 2000 pixels): its
 * colours (sampled from the image) and every size, kept in the reference's own pixels ("R"). The menu is the reference
 * scaled to the window: {@link #apply} picks the factor (window pixels per R) and the interface scale, and {@link #r}
 * turns R into layout units. So the window keeps the reference's proportions at any size, with a floor that keeps
 * small windows readable. Render thread.
 */
final class MenuStyle {
	// ---------- colours (ARGB, sampled from the reference) ----------

	static int SCRIM = 0x8C000000;
	static int WINDOW = 0xFF211E1B;
	static int HEADER = 0xFF1A1812;
	static int CONTENT_TOP = 0xFF1C1A15;
	static int CONTENT_LOW = 0xFF23201D;
	static int WINDOW_EDGE = 0xFF34302C;
	static int HEADER_RULE = 0xFF2F2D25;

	static int WORDMARK = 0xFFF6F1EB;
	static int TAB_SELECTED = 0xFF2F2D27;
	static int TAB_SELECTED_EDGE = 0xFF44403A;
	static int TAB_HOVER = 0xFF25231E;
	static int TAB_TEXT = 0xFFA0978D;
	static int TAB_TEXT_HOVER = 0xFFD8D1C8;
	static int TAB_TEXT_SELECTED = 0xFFF6F1EB;
	static int CLOSE = 0xFF2B2821;
	static int CLOSE_HOVER = 0xFF37332B;
	static int CLOSE_EDGE = 0xFF403C35;
	static int CLOSE_MARK = 0xFFD8D1C8;

	static int LABEL = 0xFF90887F;
	static int PILL = 0xFF382517;
	static int PILL_HOVER = 0xFF43301E;
	static int PILL_EDGE = 0xFF79411F;
	static int ACCENT = 0xFFFF8139;
	static int ACCENT_HOVER = 0xFFFF9150;
	static int ACCENT_PRESS = 0xFFEC722E;
	static int ON_ACCENT = 0xFF230F04;

	static int CHIP = 0xFF282520;
	static int CHIP_HOVER = 0xFF322E28;
	static int CHIP_TEXT = 0xFFAAA299;
	static int CHIP_COUNT = 0xFF7C756D;
	static int CHIP_SELECTED = 0xFFF6F1EB;
	static int CHIP_SELECTED_TEXT = 0xFF1B1812;
	static int CHIP_SELECTED_COUNT = 0xFF6E6862;
	static int VIEW = 0xFF27241E;
	static int VIEW_HOVER = 0xFF322E28;
	static int VIEW_ICON = 0xFFA0978D;
	static int SEARCH = 0xFF2B2821;
	static int SEARCH_EDGE = 0xFF3B372F;
	static int SEARCH_EDGE_HOVER = 0xFF4A4640;
	static int SEARCH_FOCUS = 0xB3FF8139;
	static int SEARCH_TEXT = 0xFFF6F1EB;
	static int SEARCH_HINT = 0xFF90887F;

	static int CARD = 0xFF2F2C27;
	static int CARD_HOVER = 0xFF34312B;
	static int CARD_EDGE = 0xFF47443F;
	static int CARD_EDGE_HOVER = 0xFF5D5952;
	static int STAR = 0xFF8E877F;
	static int STAR_HOVER = 0xFFC9C2B9;
	static int STAR_ON = 0xFFFFFFFF;
	static int BADGE = 0xFF4C3428;
	static int BADGE_TEXT = 0xFFFF9D67;
	static int ICON_ON = 0xFFFFFFFF;
	static int ICON_OFF = 0xFF898685;
	static int NAME_ON = 0xFFF1EBE3;
	static int NAME_OFF = 0xFFAAA299;
	static int OPTIONS = 0xFF403B36;
	static int OPTIONS_HOVER = 0xFF4A4540;
	static int OPTIONS_PRESS = 0xFF36322E;
	static int OPTIONS_RULE = 0xFF2D2927;
	static int OPTIONS_TEXT = 0xFFE0DAD2;
	static int ENABLED = 0xFF563B2B;
	static int ENABLED_HOVER = 0xFF624330;
	static int ENABLED_EDGE = 0xFF98552E;
	static int ENABLED_TEXT = 0xFFFFAC79;
	static int DISABLED = 0xFF35312F;
	static int DISABLED_HOVER = 0xFF3D3936;
	static int DISABLED_TEXT = 0xFF90887F;
	static int SCROLL_THUMB = 0xFF4A4541;
	static int FOCUS = 0x99FF8139;

	// ---------- a module's settings page ----------
	static int SENTENCE_TEXT = 0xFFD8D1C8;
	static int INFO = 0xFF90887F;
	static int ROW_TEXT = 0xFFECE5DC;
	static int ROW_RULE = 0xFF2A2723;
	static int SECTION_CARD = 0xFF262320;
	static int SECTION_EDGE = 0xFF3A3631;
	static int TRACK_OFF = 0xFF3A352F;
	static int KNOB_OFF = 0xFF9A9187;
	static int SEGMENTS = 0xFF1E1C18;
	static int SEGMENT_HOVER = 0xFF2E2A25;
	static int FIELD = 0xFF1E1C18;
	static int FIELD_EDGE = 0xFF3B372F;
	static int DANGER = 0xFFE5534B;
	static int DANGER_TEXT = 0xFFFFB4A0;
	/** A warning note under a setting (a key another control also uses): amber. */
	static int WARN_TEXT = 0xFFE3B46A;
	static int PREVIEW_FRAME = 0xFF1E1C18;

	static int HINT_KEY = 0xFF4D4C4C;
	static int HINT_KEY_TEXT = 0xFFFFFFFF;
	static int HINT_TEXT = 0xFFAEAEAE;
	static int EMPTY_TEXT = 0xFFD8D1C8;
	static int EMPTY_NOTE = 0xFF90887F;

	// ---------- theme ----------

	/** Corner radii, times Flint's own (the theme's roundness). */
	private static float radiusScale = 1.0F;
	private static MenuTheme theme = MenuTheme.FLINT;

	/** The theme the colours were made from. */
	static MenuTheme theme() {
		return theme;
	}

	/**
	 * Makes every colour from {@code t}: panels and text re-tinted to its surface family (Flint's charcoal keeps the
	 * sampled colours exactly), accent shades from its accent, the dim from its dim. Cheap; runs when the theme changes,
	 * never per frame.
	 */
	static void applyTheme(MenuTheme t) {
		if (t.equals(theme) && applied) {
			return;
		}
		theme = t;
		applied = true;
		WINDOW = neutral(0xFF211E1B, t);
		HEADER = neutral(0xFF1A1812, t);
		CONTENT_TOP = neutral(0xFF1C1A15, t);
		CONTENT_LOW = neutral(0xFF23201D, t);
		WINDOW_EDGE = neutral(0xFF34302C, t);
		HEADER_RULE = neutral(0xFF2F2D25, t);
		WORDMARK = neutral(0xFFF6F1EB, t);
		TAB_SELECTED = neutral(0xFF2F2D27, t);
		TAB_SELECTED_EDGE = neutral(0xFF44403A, t);
		TAB_HOVER = neutral(0xFF25231E, t);
		TAB_TEXT = neutral(0xFFA0978D, t);
		TAB_TEXT_HOVER = neutral(0xFFD8D1C8, t);
		TAB_TEXT_SELECTED = neutral(0xFFF6F1EB, t);
		CLOSE = neutral(0xFF2B2821, t);
		CLOSE_HOVER = neutral(0xFF37332B, t);
		CLOSE_EDGE = neutral(0xFF403C35, t);
		CLOSE_MARK = neutral(0xFFD8D1C8, t);
		LABEL = neutral(0xFF90887F, t);
		CHIP = neutral(0xFF282520, t);
		CHIP_HOVER = neutral(0xFF322E28, t);
		CHIP_TEXT = neutral(0xFFAAA299, t);
		CHIP_COUNT = neutral(0xFF7C756D, t);
		CHIP_SELECTED = neutral(0xFFF6F1EB, t);
		CHIP_SELECTED_TEXT = neutral(0xFF1B1812, t);
		CHIP_SELECTED_COUNT = neutral(0xFF6E6862, t);
		VIEW = neutral(0xFF27241E, t);
		VIEW_HOVER = neutral(0xFF322E28, t);
		VIEW_ICON = neutral(0xFFA0978D, t);
		SEARCH = neutral(0xFF2B2821, t);
		SEARCH_EDGE = neutral(0xFF3B372F, t);
		SEARCH_EDGE_HOVER = neutral(0xFF4A4640, t);
		SEARCH_TEXT = neutral(0xFFF6F1EB, t);
		SEARCH_HINT = neutral(0xFF90887F, t);
		CARD = neutral(0xFF2F2C27, t);
		CARD_HOVER = neutral(0xFF34312B, t);
		CARD_EDGE = neutral(0xFF47443F, t);
		CARD_EDGE_HOVER = neutral(0xFF5D5952, t);
		STAR = neutral(0xFF8E877F, t);
		STAR_HOVER = neutral(0xFFC9C2B9, t);
		ICON_OFF = neutral(0xFF898685, t);
		NAME_ON = neutral(0xFFF1EBE3, t);
		NAME_OFF = neutral(0xFFAAA299, t);
		OPTIONS = neutral(0xFF403B36, t);
		OPTIONS_HOVER = neutral(0xFF4A4540, t);
		OPTIONS_PRESS = neutral(0xFF36322E, t);
		OPTIONS_RULE = neutral(0xFF2D2927, t);
		OPTIONS_TEXT = neutral(0xFFE0DAD2, t);
		DISABLED = neutral(0xFF35312F, t);
		DISABLED_HOVER = neutral(0xFF3D3936, t);
		DISABLED_TEXT = neutral(0xFF90887F, t);
		SCROLL_THUMB = neutral(0xFF4A4541, t);
		SENTENCE_TEXT = neutral(0xFFD8D1C8, t);
		INFO = neutral(0xFF90887F, t);
		ROW_TEXT = neutral(0xFFECE5DC, t);
		ROW_RULE = neutral(0xFF2A2723, t);
		SECTION_CARD = neutral(0xFF262320, t);
		SECTION_EDGE = neutral(0xFF3A3631, t);
		TRACK_OFF = neutral(0xFF3A352F, t);
		KNOB_OFF = neutral(0xFF9A9187, t);
		SEGMENTS = neutral(0xFF1E1C18, t);
		SEGMENT_HOVER = neutral(0xFF2E2A25, t);
		FIELD = neutral(0xFF1E1C18, t);
		FIELD_EDGE = neutral(0xFF3B372F, t);
		PREVIEW_FRAME = neutral(0xFF1E1C18, t);
		HINT_KEY = neutral(0xFF4D4C4C, t);
		HINT_TEXT = neutral(0xFFAEAEAE, t);
		EMPTY_TEXT = neutral(0xFFD8D1C8, t);
		EMPTY_NOTE = neutral(0xFF90887F, t);
		int a = 0xFF000000 | t.accentRgb();
		ACCENT = a;
		ACCENT_HOVER = Palette.lerp(a, 0xFFFFFFFF, 0.10F);
		ACCENT_PRESS = Palette.lerp(a, 0xFF000000, 0.08F);
		ON_ACCENT = Palette.lerp(a, 0xFF000000, 0.88F);
		PILL = Palette.lerp(WINDOW, a, 0.16F);
		PILL_HOVER = Palette.lerp(WINDOW, a, 0.22F);
		PILL_EDGE = Palette.lerp(WINDOW, a, 0.45F);
		SEARCH_FOCUS = Palette.withAlpha(a, 0.70F);
		BADGE = Palette.lerp(CARD, a, 0.22F);
		BADGE_TEXT = Palette.lerp(a, 0xFFFFFFFF, 0.20F);
		ENABLED = Palette.lerp(CARD, a, 0.24F);
		ENABLED_HOVER = Palette.lerp(CARD, a, 0.30F);
		ENABLED_EDGE = Palette.lerp(CARD, a, 0.58F);
		ENABLED_TEXT = Palette.lerp(a, 0xFFFFFFFF, 0.28F);
		FOCUS = Palette.withAlpha(a, 0.60F);
		SCRIM = Palette.withAlpha(0xFF000000, t.dim() / 100.0F);
		radiusScale = t.roundness() / 100.0F;
	}

	private static boolean applied;

	private static int neutral(int base, MenuTheme t) {
		return t.tint(base);
	}

	// ---------- type: Plus Jakarta Sans, sizes in R (from the reference's cap heights) ----------

	enum Type {
		WORDMARK(FlintFonts.UI_HEAVY, 36, 0.20F),
		TAB(FlintFonts.UI_BOLD, 24, 0.20F),
		BUTTON(FlintFonts.UI_BOLD, 23, 0.18F),
		CHIP(FlintFonts.UI_BOLD, 22.5F, 0.14F),
		COUNT(FlintFonts.UI_SEMI, 19, 0.04F),
		SEARCH(FlintFonts.UI_SEMI, 26, 0),
		NAME(FlintFonts.UI_SEMI, 33.5F, 0),
		CARD_BUTTON(FlintFonts.UI_BOLD, 22.5F, 0.22F),
		BADGE(FlintFonts.UI_BOLD, 19.5F, 0.10F),
		HINT_KEY(FlintFonts.UI_BOLD, 22, 0.10F),
		HINT(FlintFonts.UI, 25, 0.01F),
		EMPTY(FlintFonts.UI_SEMI, 31, 0),
		EMPTY_NOTE(FlintFonts.UI, 25, 0),
		// ---------- a module's settings page ----------
		SENTENCE(FlintFonts.UI_SEMI, 27, 0),
		PAGE_BUTTON(FlintFonts.UI_BOLD, 23, 0.07F),
		SECTION_TITLE(FlintFonts.UI_BOLD, 26, 0.01F),
		ROW_LABEL(FlintFonts.UI_SEMI, 27, 0),
		ROW_NOTE(FlintFonts.UI, 21.5F, 0),
		SEGMENT(FlintFonts.UI_SEMI, 22.5F, 0.04F),
		VALUE(FlintFonts.UI_SEMI, 23, 0),
		CAPTION(FlintFonts.UI, 21.5F, 0);

		private final FlintFonts role;
		private final float size;
		final float tracking;
		private FontFace face;

		Type(FlintFonts role, float size, float tracking) {
			this.role = role;
			this.size = size;
			this.tracking = tracking;
		}

		FontFace face() {
			FontFace current = face;
			if (current == null) {
				current = ScaledFont.of(role, size * units);
				face = current;
			}
			return current;
		}
	}

	/** A piece of text in one {@link Type}: its line is remade only when the text or the type's face changes. */
	static final class Label {
		private final Type type;
		private String text;
		private TextLine line;
		private FontFace madeWith;

		Label(Type type, String text) {
			this.type = type;
			this.text = text;
		}

		TextLine line() {
			FontFace face = type.face();
			if (line == null || madeWith != face) {
				line = new TextLine(face, text, type.tracking);
				madeWith = face;
			}
			return line;
		}

		String text() {
			return text;
		}

		void set(String value) {
			if (!value.equals(text)) {
				text = value;
				line = null;
			}
		}

		/** Width in layout units (letter spacing after the last letter left out). */
		float width(Canvas c) {
			TextLine shown = line();
			float trailing = type.tracking * type.size * units;
			return Math.max(0, (float) c.width(shown) / c.scale() - trailing);
		}
	}

	// ---------- scale ----------

	/** The smallest factor (window pixels per R): below it, text would be too small to read, so the window crops. */
	private static final float MIN_FACTOR = 0.43F;
	private static final float MAX_FACTOR = 1.0F;

	/** Window pixels per R. */
	private static float factor = 0.6F;
	/** The interface scale (window pixels per layout unit). */
	private static int scale = 2;
	/** Layout units per R. */
	private static float units = 0.3F;

	private MenuStyle() {
	}

	/**
	 * Fits the reference to a {@code pxW × pxH} window: the factor that makes the reference fill it as it fills its own
	 * 3200 × 2000 canvas (in steps of 0.01, between 0.43 and 1), and a whole interface scale near a third of it. Fonts are
	 * remade only when the result changes. Returns the interface scale.
	 */
	static int apply(int pxW, int pxH) {
		float f = Math.clamp(Math.round(Math.min(pxW / 3200.0F, pxH / 2000.0F) * 100) / 100.0F, MIN_FACTOR, MAX_FACTOR);
		int s = Math.max(1, Math.round(f * 3.2F));
		if (f != factor || s != scale || Type.WORDMARK.face == null) {
			factor = f;
			scale = s;
			units = f / s;
			for (Type type : Type.values()) {
				type.face = null;
			}
		}
		return scale;
	}

	/** {@code ref} reference pixels in layout units. */
	static float r(float ref) {
		return ref * units;
	}

	/** A hairline of the reference (2 R) in whole window pixels, never under one. */
	static int edge() {
		return Math.max(1, Math.round(2 * factor));
	}

	// ---------- drawing helpers (pixel-snapped through the canvas) ----------

	/** A corner radius in this theme (its roundness times {@code radius}). */
	static float round(float radius) {
		return radius * radiusScale;
	}

	static void box(Canvas c, float x, float y, float w, float h, float radius, int argb) {
		radius = round(radius);
		int x0 = c.px(x);
		int y0 = c.px(y);
		c.roundRect(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(radius), argb);
	}

	static void framed(Canvas c, float x, float y, float w, float h, float radius, int fill, int border) {
		radius = round(radius);
		int x0 = c.px(x);
		int y0 = c.px(y);
		c.panel(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(radius), edge(), border, fill);
	}

	static void ring(Canvas c, float x, float y, float w, float h, float radius, int thickness, int argb) {
		radius = round(radius);
		int x0 = c.px(x);
		int y0 = c.px(y);
		c.ring(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(radius), thickness, argb);
	}

	/** A keyboard-focus ring just outside the box. */
	static void focusRing(Canvas c, float x, float y, float w, float h, float radius, float focus) {
		if (focus > 0.01F) {
			float out = r(5);
			ring(c, x - out, y - out, w + 2 * out, h + 2 * out, radius + out, Math.max(1, Math.round(3 * factor)),
				Palette.withAlpha(FOCUS, focus));
		}
	}

	/** Draws {@code label} starting at {@code x}, its capitals centred on {@code centerY}. */
	static void text(Canvas c, Label label, float x, float centerY, int argb) {
		TextLine line = label.line();
		c.text(line, c.px(x), c.baselineFor(line, c.px(centerY)), argb);
	}

	/** Draws {@code label} centred on ({@code centerX}, {@code centerY}), cut short with an ellipsis past {@code maxW}. */
	static void centered(Canvas c, Label label, float centerX, float centerY, int argb, float maxW) {
		TextLine line = label.line();
		int max = c.px(maxW);
		int shown = Math.min(c.width(line, max), Math.round(label.width(c) * c.scale()));
		c.text(line, c.px(centerX) - shown / 2, c.baselineFor(line, c.px(centerY)), argb, max);
	}

	/** Draws {@code label} from {@code x}, its capitals centred on {@code centerY}, cut short with an ellipsis past {@code maxW}. */
	static void fitted(Canvas c, Label label, float x, float centerY, int argb, float maxW) {
		TextLine line = label.line();
		c.text(line, c.px(x), c.baselineFor(line, c.px(centerY)), argb, Math.max(1, c.px(maxW)));
	}

	static void icon(Canvas c, Icon icon, float centerX, float centerY, float size, int argb) {
		int px = Math.max(1, c.px(size));
		c.icon(icon, c.px(centerX) - px / 2, c.px(centerY) - px / 2, px, argb);
	}
}

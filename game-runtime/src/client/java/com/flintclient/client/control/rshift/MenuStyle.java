package com.flintclient.client.control.rshift;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;

/**
 * The Right Shift menu's look, Dawn's: near-black slate blocks with a lit ring, white text, grey for what is off, green for
 * what is on and the theme's accent (Flint's orange) for what is chosen; every word in Minecraft's own font with no shadow.
 * Sizes are kept in the reference's own pixels ("R", from its 3200 × 2000 canvas); {@link #d} takes Dawn's design pixels. The menu is the reference
 * scaled to the window: {@link #apply} picks the factor (window pixels per R) and the interface scale, and {@link #r}
 * turns R into layout units. So the window keeps the reference's proportions at any size, with a floor that keeps
 * small windows readable. Render thread.
 */
final class MenuStyle {
	// ---------- colours (ARGB, sampled from the reference) ----------

	static int SCRIM = 0x8C000000;
	static int WINDOW = 0xFF080808;
	static int HEADER = 0xFF050505;
	static int CONTENT_TOP = 0xFF0C0C0C;
	static int CONTENT_LOW = 0xFF0F0F0F;
	static int WINDOW_EDGE = 0xFF1F1F1F;
	static int HEADER_RULE = 0xFF1A1A1A;

	static int WORDMARK = 0xFFF5F5F5;
	/** A window tab that isn't open: a square with its icon. */
	static int TAB = 0xFF1A1A1A;
	static int TAB_SELECTED = 0xFF141414;
	static int TAB_SELECTED_EDGE = 0xFF2A2A2A;
	static int TAB_HOVER = 0xFF121212;
	static int TAB_TEXT = 0xFF8A8A8A;
	static int TAB_TEXT_HOVER = 0xFFD9D9D9;
	static int TAB_TEXT_SELECTED = 0xFFFFFFFF;
	static int CLOSE = 0xFF121212;
	static int CLOSE_HOVER = 0xFF1C1C1C;
	static int CLOSE_EDGE = 0xFF262626;
	static int CLOSE_MARK = 0xFFD9D9D9;

	static int LABEL = 0xFF7A7A7A;
	static int PILL = 0xFF382517;
	static int PILL_HOVER = 0xFF43301E;
	static int PILL_EDGE = 0xFF79411F;
	static int ACCENT = 0xFFFF8139;
	static int ACCENT_HOVER = 0xFFFF9150;
	static int ACCENT_PRESS = 0xFFEC722E;
	static int ON_ACCENT = 0xFF230F04;

	static int CHIP = 0xFF121212;
	static int CHIP_HOVER = 0xFF1A1A1A;
	static int CHIP_TEXT = 0xFF9A9A9A;
	static int CHIP_COUNT = 0xFF666666;
	static int CHIP_SELECTED = 0xFFF5F5F5;
	static int CHIP_SELECTED_TEXT = 0xFF0A0A0A;
	static int CHIP_SELECTED_COUNT = 0xFF4A4A4A;
	static int VIEW = 0xFF121212;
	static int VIEW_HOVER = 0xFF1A1A1A;
	static int VIEW_ICON = 0xFF9A9A9A;
	static int SEARCH = 0xFF0E0E0E;
	static int SEARCH_EDGE = 0xFF242424;
	static int SEARCH_EDGE_HOVER = 0xFF303030;
	static int SEARCH_FOCUS = 0xB3FF8139;
	static int SEARCH_TEXT = 0xFFF0F0F0;
	static int SEARCH_HINT = 0xFF6E6E6E;

	static int CARD = 0xFF0F0F0F;
	static int CARD_HOVER = 0xFF161616;
	static int CARD_EDGE = 0xFF1D1D1D;
	static int CARD_EDGE_HOVER = 0xFF2E2E2E;
	static int STAR = 0xFF5E5E5E;
	static int STAR_HOVER = 0xFFBDBDBD;
	static int STAR_ON = 0xFFFFFFFF;
	static int BADGE = 0xFF4C3428;
	static int BADGE_TEXT = 0xFFFF9D67;
	static int ICON_ON = 0xFFF0F0F0;
	static int ICON_OFF = 0xFF6A6A6A;
	static int NAME_ON = 0xFFF0F0F0;
	static int NAME_OFF = 0xFF8A8A8A;
	static int OPTIONS = 0xFF1A1A1A;
	static int OPTIONS_HOVER = 0xFF222222;
	static int OPTIONS_PRESS = 0xFF151515;
	static int OPTIONS_RULE = 0xFF161616;
	static int OPTIONS_TEXT = 0xFFE0E0E0;
	static int ENABLED = 0xFF563B2B;
	static int ENABLED_HOVER = 0xFF624330;
	static int ENABLED_EDGE = 0xFF98552E;
	static int ENABLED_TEXT = 0xFFFFAC79;
	static int DISABLED = 0xFF181818;
	static int DISABLED_HOVER = 0xFF212121;
	static int DISABLED_TEXT = 0xFFE6E6E6;
	/** A mod card's status while the mod is off: a deep red with soft red text. */
	static int STATUS_OFF = 0xFF2A1416;
	static int STATUS_OFF_HOVER = 0xFF351A1C;
	static int STATUS_OFF_TEXT = 0xFFFF8A84;
	/** A settings row's own tile on a page. */
	static int TILE = 0xFF0C0C0C;
	static int TILE_HOVER = 0xFF121212;
	static int TILE_EDGE = 0xFF181818;
	static int SCROLL_THUMB = 0xFF2A2A2A;
	static int FOCUS = 0x99FF8139;

	// ---------- a module's settings page ----------
	static int SENTENCE_TEXT = 0xFFBDBDBD;
	static int INFO = 0xFF7A7A7A;
	static int ROW_TEXT = 0xFFE8E8E8;
	static int ROW_RULE = 0xFF151515;
	static int SECTION_CARD = 0xFF0D0D0D;
	static int SECTION_EDGE = 0xFF1C1C1C;
	static int TRACK_OFF = 0xFF262626;
	static int KNOB_OFF = 0xFF7A7A7A;
	static int SEGMENTS = 0xFF0B0B0B;
	static int SEGMENT_HOVER = 0xFF1A1A1A;
	static int FIELD = 0xFF0B0B0B;
	static int FIELD_EDGE = 0xFF262626;
	static int DANGER = 0xFFE5534B;
	static int DANGER_TEXT = 0xFFFFB4A0;
	/** A warning note under a setting (a key another control also uses): amber. */
	static int WARN_TEXT = 0xFFE3B46A;
	static int PREVIEW_FRAME = 0xFF0B0B0B;

	// ---------- Dawn's design tokens ----------
	/** primary-bg and secondary-bg: a block's fill runs from the second (top) to the first (bottom). */
	static int BG = 0xFF0D0F12;
	static int BG2 = 0xFF0F1215;
	/** A block's 3 px ring: light at its top-left and bottom-right ends, dark between. */
	static int RING_LIGHT = 0xFF171B20;
	static int RING_DARK = 0xFF0E1013;
	/** The light a block takes under the pointer, from its top-left corner. */
	static int HOVER_LIGHT = 0xFF3F4956;
	/** Text, and green for what is on (the status button's fill is it at 40%). */
	static int TEXT = 0xFFEDEDED;
	static int SUCCESS = 0xFF2A9328;
	static int SUCCESS_TEXT = 0xFF34A832;
	static int WARN = 0xFFF8FC42;

	// ---------- blocks and controls (the reference's design tokens, on black) ----------
	/** A block's 3 px ring: lighter at the top than at the bottom. */
	static int BLOCK_RING_TOP = 0xFF1A1A1A;
	static int BLOCK_RING_BOTTOM = 0xFF0D0D0D;
	/** A block's fill, darkening downward, and the light that comes in from the top under the pointer. */
	static int BLOCK_FILL_TOP = 0xFF131313;
	static int BLOCK_FILL_BOTTOM = 0xFF090909;
	static int BLOCK_HOVER_TOP = 0xFF383838;
	/** Secondary text and idle controls: the reference's neutral grey. */
	static int NEUTRAL = 0xFF7A7A7A;
	/** A control's soft fill (5% of the text colour), on a block (10%), and an input's edge (15%). */
	static int SOFT = 0x0DFFFFFF;
	static int SOFT_ON_BLOCK = 0x1AFFFFFF;
	static int SOFT_EDGE = 0x26FFFFFF;
	/** Red for what is off. */
	static int RED = 0xFFDD3538;

	static int HINT_KEY = 0xFF2A2A2A;
	static int HINT_KEY_TEXT = 0xFFFFFFFF;
	static int HINT_TEXT = 0xFFAEAEAE;
	static int EMPTY_TEXT = 0xFFD9D9D9;
	static int EMPTY_NOTE = 0xFF7A7A7A;

	// ---------- theme ----------

	private static MenuTheme theme = MenuTheme.FLINT;
	private static boolean applied;
	/** Every colour above, for the blend from one theme to the next (the static, non-final int fields in capitals). */
	private static final java.lang.reflect.Field[] COLORS = colorFields();
	private static int[] blendFrom = new int[0];
	private static int[] blendTo = new int[0];
	/** When the current blend began (milliseconds), or -1 when none is running. */
	private static long blendStart = -1;
	private static final long BLEND_MILLIS = 240;

	/** The theme the colours are made from. */
	static MenuTheme theme() {
		return theme;
	}

	/**
	 * Makes every colour from {@code t}'s look. A change of theme blends from the old colours to the new over a quarter of a
	 * second (eased), and a change of font remakes the type. Called each frame by the screens: cheap unless a blend is running.
	 */
	static void applyTheme(MenuTheme t) {
		if (!applied) {
			applied = true;
			theme = t;
			computeColors(t.look());
		} else if (t.look() != theme.look()) {
			int[] start = snapshot();
			theme = t;
			computeColors(t.look());
			blendTo = snapshot();
			blendFrom = start;
			blendStart = Clock.millis();
			restore(blendFrom);
		}
		if (blendStart >= 0) {
			float k = (Clock.millis() - blendStart) / (float) BLEND_MILLIS;
			if (k >= 1) {
				restore(blendTo);
				blendStart = -1;
			} else {
				float e = k * k * (3 - 2 * k);
				try {
					for (int i = 0; i < COLORS.length; i++) {
						COLORS[i].setInt(null, Palette.lerp(blendFrom[i], blendTo[i], e));
					}
				} catch (IllegalAccessException impossible) {
					restore(blendTo);
					blendStart = -1;
				}
			}
		}
	}

	private static java.lang.reflect.Field[] colorFields() {
		List<java.lang.reflect.Field> out = new ArrayList<>();
		for (java.lang.reflect.Field f : MenuStyle.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (f.getType() == int.class && java.lang.reflect.Modifier.isStatic(m) && !java.lang.reflect.Modifier.isFinal(m)
				&& f.getName().equals(f.getName().toUpperCase(java.util.Locale.ROOT))) {
				out.add(f);
			}
		}
		return out.toArray(new java.lang.reflect.Field[0]);
	}

	private static int[] snapshot() {
		int[] out = new int[COLORS.length];
		try {
			for (int i = 0; i < COLORS.length; i++) {
				out[i] = COLORS[i].getInt(null);
			}
		} catch (IllegalAccessException impossible) {
			throw new IllegalStateException(impossible);
		}
		return out;
	}

	private static void restore(int[] values) {
		try {
			for (int i = 0; i < COLORS.length && i < values.length; i++) {
				COLORS[i].setInt(null, values[i]);
			}
		} catch (IllegalAccessException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** {@code rgb} opaque. */
	private static int solid(int rgb) {
		return 0xFF000000 | rgb;
	}

	/** Dawn's own tokens for Flint's look; another look's, made the same way from its colours. */
	private static int[] tokens(MenuTheme.Look l) {
		if (l == MenuTheme.Look.FLINT) {
			return new int[] {0xFF0D0F12, 0xFF0F1215, 0xFF171B20, 0xFF0E1013, 0xFF3F4956, 0xFFEDEDED, 0xFFF97316};
		}
		int bg = Palette.lerp(solid(l.background), solid(l.surface1), 0.6F);
		return new int[] {bg, solid(l.surface1), solid(l.surface3), Palette.lerp(bg, solid(l.surface1), 0.5F),
			Palette.lerp(solid(l.surface4), solid(l.ink), 0.18F), solid(l.ink), solid(l.accent)};
	}

	/**
	 * Sets every colour from a look, as Dawn's stylesheet does: blocks in the two backgrounds with a lit ring, text and its
	 * grey, controls as the text at 5%, 10% and 15% over the background, green for what is on, the accent for what is chosen.
	 */
	private static void computeColors(MenuTheme.Look l) {
		int[] t = tokens(l);
		BG = t[0];
		BG2 = t[1];
		RING_LIGHT = t[2];
		RING_DARK = t[3];
		HOVER_LIGHT = t[4];
		TEXT = t[5];
		int a = t[6];
		int bg = BG;
		int text = TEXT;
		java.util.function.DoubleFunction<Integer> ink = k -> Palette.lerp(bg, text, (float) k);
		NEUTRAL = l == MenuTheme.Look.FLINT ? 0xFF7A7A7A : ink.apply(0.48);
		SUCCESS = 0xFF2A9328;
		SUCCESS_TEXT = 0xFF34A832;
		WARN = 0xFFF8FC42;
		RED = 0xFFDD3538;
		ACCENT = a;
		ACCENT_HOVER = Palette.lerp(a, 0xFFFFFFFF, 0.12F);
		ACCENT_PRESS = Palette.lerp(a, 0xFF000000, 0.12F);
		ON_ACCENT = TEXT;
		SOFT = Palette.withAlpha(TEXT, 0.05F);
		SOFT_ON_BLOCK = Palette.withAlpha(TEXT, 0.10F);
		SOFT_EDGE = Palette.withAlpha(TEXT, 0.15F);
		int soft = ink.apply(0.05);
		int soft2 = ink.apply(0.08);

		SCRIM = 0x8C000000;
		WINDOW = bg;
		HEADER = bg;
		CONTENT_TOP = BG2;
		CONTENT_LOW = bg;
		WINDOW_EDGE = RING_LIGHT;
		HEADER_RULE = RING_LIGHT;
		WORDMARK = TEXT;
		TAB = bg;
		TAB_SELECTED = a;
		TAB_SELECTED_EDGE = a;
		TAB_HOVER = HOVER_LIGHT;
		TAB_TEXT = NEUTRAL;
		TAB_TEXT_HOVER = TEXT;
		TAB_TEXT_SELECTED = TEXT;
		CLOSE = Palette.over(Palette.withAlpha(a, 0.10F), bg);
		CLOSE_HOVER = Palette.over(Palette.withAlpha(a, 0.18F), bg);
		CLOSE_EDGE = a;
		CLOSE_MARK = a;
		LABEL = NEUTRAL;
		PILL = Palette.over(Palette.withAlpha(a, 0.10F), bg);
		PILL_HOVER = Palette.over(Palette.withAlpha(a, 0.16F), bg);
		PILL_EDGE = a;
		CHIP = soft;
		CHIP_HOVER = soft2;
		CHIP_TEXT = NEUTRAL;
		CHIP_COUNT = ink.apply(0.3);
		CHIP_SELECTED = a;
		CHIP_SELECTED_TEXT = TEXT;
		CHIP_SELECTED_COUNT = Palette.lerp(a, TEXT, 0.5F);
		VIEW = soft;
		VIEW_HOVER = soft2;
		VIEW_ICON = NEUTRAL;
		// Inputs: the text at 5% with no edge; the pointer lights the edge, typing rings it in the accent.
		SEARCH = soft;
		SEARCH_EDGE = soft;
		SEARCH_EDGE_HOVER = ink.apply(0.15);
		SEARCH_FOCUS = Palette.withAlpha(a, 0.70F);
		SEARCH_TEXT = TEXT;
		SEARCH_HINT = NEUTRAL;
		CARD = bg;
		CARD_HOVER = HOVER_LIGHT;
		CARD_EDGE = RING_LIGHT;
		CARD_EDGE_HOVER = HOVER_LIGHT;
		STAR = NEUTRAL;
		STAR_HOVER = TEXT;
		STAR_ON = a;
		BADGE = SUCCESS;
		BADGE_TEXT = TEXT;
		ICON_ON = TEXT;
		ICON_OFF = NEUTRAL;
		NAME_ON = TEXT;
		NAME_OFF = NEUTRAL;
		OPTIONS = soft;
		OPTIONS_HOVER = soft2;
		OPTIONS_PRESS = ink.apply(0.03);
		OPTIONS_RULE = RING_LIGHT;
		OPTIONS_TEXT = TEXT;
		// Status: on is the green at 40% with green text; off is the text at 5% with grey text.
		ENABLED = Palette.over(Palette.withAlpha(SUCCESS, 0.40F), bg);
		ENABLED_HOVER = Palette.over(Palette.withAlpha(SUCCESS, 0.50F), bg);
		ENABLED_EDGE = SUCCESS;
		ENABLED_TEXT = SUCCESS_TEXT;
		DISABLED = soft;
		DISABLED_HOVER = soft2;
		DISABLED_TEXT = NEUTRAL;
		STATUS_OFF = soft;
		STATUS_OFF_HOVER = soft2;
		STATUS_OFF_TEXT = NEUTRAL;
		TILE = BG2;
		TILE_HOVER = Palette.lerp(BG2, HOVER_LIGHT, 0.35F);
		TILE_EDGE = RING_LIGHT;
		SCROLL_THUMB = 0xFF33363B;
		FOCUS = Palette.withAlpha(a, 0.60F);
		SENTENCE_TEXT = NEUTRAL;
		INFO = NEUTRAL;
		ROW_TEXT = TEXT;
		ROW_RULE = RING_LIGHT;
		SECTION_CARD = bg;
		SECTION_EDGE = RING_LIGHT;
		TRACK_OFF = ink.apply(0.15);
		KNOB_OFF = NEUTRAL;
		SEGMENTS = soft;
		SEGMENT_HOVER = soft2;
		FIELD = soft;
		FIELD_EDGE = ink.apply(0.15);
		DANGER = RED;
		DANGER_TEXT = Palette.lerp(RED, TEXT, 0.35F);
		WARN_TEXT = WARN;
		PREVIEW_FRAME = bg;
		HINT_KEY = soft2;
		HINT_KEY_TEXT = TEXT;
		HINT_TEXT = NEUTRAL;
		EMPTY_TEXT = TEXT;
		EMPTY_NOTE = NEUTRAL;
		BLOCK_RING_TOP = RING_LIGHT;
		BLOCK_RING_BOTTOM = RING_DARK;
		BLOCK_FILL_TOP = BG2;
		BLOCK_FILL_BOTTOM = bg;
		BLOCK_HOVER_TOP = HOVER_LIGHT;
	}

	// ---------- type: Minecraft's own font, at Dawn's sizes (design pixels) ----------

	/**
	 * The menu's type: Minecraft's own font, never with a shadow, every size at one pixel scale for the window
	 * ({@link #pixelScale}) so no label drops a step below the others; only Dawn's 10 px marks (the NEW badge, counts) are a
	 * step smaller. Its hierarchy comes from colour and placement, as Dawn's.
	 */
	enum Type {
		WORDMARK(20),
		/** The open tab's name in the header, over the game. */
		HEADER_TITLE(24),
		/** The date, time, player and profile blocks. */
		PILL(16),
		PILL_NOTE(14),
		/** The open tab's name: 16, black, upper case. */
		TAB(16),
		/** The opener's big buttons (EDIT FLINT MODS, HUDS). */
		HUB_BUTTON(16),
		/** A default button's label. */
		BUTTON(14),
		CHIP(14),
		COUNT(10),
		SEARCH(16),
		/** A card's name. */
		NAME(20),
		/** The card's status button. */
		CARD_BUTTON(14),
		/** The NEW badge. */
		BADGE(10),
		HINT_KEY(12),
		HINT(14),
		EMPTY(18),
		EMPTY_NOTE(14),
		// ---------- a module's settings page ----------
		/** The description beside the page's icon. */
		SENTENCE(16),
		PAGE_BUTTON(14),
		/** A section's title. */
		SECTION_TITLE(16),
		/** A settings page's name, in its title block. */
		PAGE_TITLE(20),
		/** A row's name. */
		ROW_LABEL(16),
		ROW_NOTE(12),
		/** A radio button's label (medium buttons). */
		SEGMENT(14),
		/** A select's value and a key bind's key. */
		VALUE(12),
		/** A slider's value box. */
		RANGE(12),
		CAPTION(12);

		/** The size in R (1.8 per design pixel). */
		private final float size;
		/** Dawn's size in design pixels. */
		private final float designPx;
		final float tracking = 0;
		private FontFace face;

		Type(float designPx) {
			this.size = designPx * 1.8F;
			this.designPx = designPx;
		}

		FontFace face() {
			FontFace current = face;
			if (current == null) {
				int s = pixelScale();
				current = PixelFace.fixed(designPx <= 10 ? Math.max(1, s - 1) : s);
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

	/**
	 * Screen pixels per pixel of Minecraft's font for the menu's text: 2 up to about a 1440p window, then growing with it
	 * (3 at 4K), so text keeps close to Dawn's sizes and never falls to 1, where it is too small to read.
	 */
	static int pixelScale() {
		float windowPerDesign = factor * 1.8F;
		return Math.max(2, Math.round(windowPerDesign * 1.6F));
	}

	private MenuStyle() {
	}

	/**
	 * Fits the reference to a {@code pxW × pxH} window: the factor that makes the reference fill it as it fills its own
	 * 3200 × 2000 canvas (in steps of 0.01, between 0.43 and 1), and a whole interface scale near a third of it. Fonts are
	 * remade only when the result changes. Returns the interface scale.
	 */
	static int apply(int pxW, int pxH) {
		int s = Math.max(1, Math.round(fit(pxW, pxH) * 3.2F));
		applyAt(pxW, pxH, s);
		return scale;
	}

	/**
	 * The reference's proportions at an interface scale the screen chose itself ({@code s} window pixels per layout unit):
	 * the HUD page keeps the kit's own scale for the editor's marks and tooltips, and its controls still size from R.
	 */
	static void applyAt(int pxW, int pxH, int s) {
		float f = fit(pxW, pxH);
		if (f != factor || s != scale || Type.WORDMARK.face == null) {
			factor = f;
			scale = s;
			units = f / s;
			for (Type type : Type.values()) {
				type.face = null;
			}
		}
	}

	/** The reference's factor for a {@code pxW × pxH} window, times General > UI Scaling (the window still bounds the menu). */
	private static float fit(int pxW, int pxH) {
		float zoom = 1;
		try {
			zoom = ControlState.settings().game(GameOptions.MENU_SCALE);
		} catch (RuntimeException | LinkageError ignored) {
			// Settings not loaded yet: the reference size.
		}
		return Math.clamp(Math.round(Math.min(pxW / 3200.0F, pxH / 2000.0F) * zoom * 100) / 100.0F, MIN_FACTOR, MAX_FACTOR);
	}

	/**
	 * A page's title block across its {@code bar}-tall header from {@code left} to {@code right} (64 design px, centred), as
	 * every settings page has; returns where its text starts.
	 */
	static float titleBlock(Canvas c, float left, float y, float right, float bar) {
		float bh = d(64);
		block(c, left, y + (bar - bh) / 2, right - left, bh, radius(), 0, 0);
		return left + d(20);
	}

	/** Dawn's tooltip: the panel colour (#2C3033), 8 design pixels round (in the kit's units before {@link #tooltipScale}), white title, grey text. */
	static com.flintclient.client.ui.kit.KitScreen.TooltipLook tooltipLook() {
		int panel = theme.look() == MenuTheme.Look.FLINT ? 0xFF2C3033 : Palette.lerp(BG, TEXT, 0.13F);
		return new com.flintclient.client.ui.kit.KitScreen.TooltipLook(panel, 0, d(8) / tooltipScale(), TEXT, NEUTRAL);
	}

	/** How much larger than the kit's own the menu's tooltips are: their type as large as a row's note. */
	static float tooltipScale() {
		return Math.max(1, r(26) / 5.2F);
	}

	/** {@code ref} reference pixels in layout units. */
	static float r(float ref) {
		return ref * units;
	}

	/** {@code px} of the design the controls are drawn to (pixels of a 1920 × 1080 window) in layout units. */
	static float d(float px) {
		return r(px * 1.8F);
	}

	/** The design's 8 px corner. */
	static float radius() {
		return d(8);
	}

	/**
	 * A block, the menu's one surface, in Flint's own finish: a fine edge (lighter at the top, as if lit from above) around a
	 * fill that deepens downward, with a soft band of light along its top. {@code hover} warms the edge and lifts the light;
	 * {@code active} turns it all the accent.
	 */
	static void block(Canvas c, float x, float y, float w, float h, float radius, float hover, float active) {
		int x0 = c.px(x);
		int y0 = c.px(y);
		int pw = c.px(x + w) - x0;
		int ph = c.px(y + h) - y0;
		int rp = c.px(radius);
		if (active >= 0.999F) {
			c.roundRect(x0, y0, pw, ph, rp, ACCENT);
			return;
		}
		int b = Math.max(1, c.px(d(1.25F)));
		int edgeTop = Palette.lerp(Palette.lerp(RING_LIGHT, HOVER_LIGHT, 0.35F), Palette.lerp(HOVER_LIGHT, ACCENT, 0.35F), hover);
		int edgeLow = Palette.lerp(RING_DARK, Palette.lerp(RING_LIGHT, ACCENT, 0.25F), hover);
		c.roundGradient(x0, y0, pw, ph, rp, edgeTop, edgeLow);
		c.roundGradient(x0 + b, y0 + b, pw - 2 * b, ph - 2 * b, Math.max(0, rp - b), BG2, BG);
		// The top light: strongest at the edge, gone a third of the way down (or 40 design pixels, whichever is less).
		int band = Math.max(1, Math.min((ph - 2 * b) / 3, c.px(d(40))));
		c.roundGradient(x0 + b, y0 + b, pw - 2 * b, band, Math.max(0, rp - b), Palette.withAlpha(TEXT, 0.035F + 0.045F * hover),
			Palette.withAlpha(TEXT, 0));
		if (active > 0.001F) {
			c.roundRect(x0, y0, pw, ph, rp, Palette.withAlpha(ACCENT, active));
		}
	}

	/** A block held down: it dims a touch, as if pressed into the window. */
	static void pressed(Canvas c, float x, float y, float w, float h, float radius, float press) {
		if (press > 0.01F) {
			int x0 = c.px(x);
			int y0 = c.px(y);
			c.roundRect(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(radius), Palette.withAlpha(0xFF000000, 0.22F * press));
		}
	}

	/** A chosen block's 2 px accent ring, just inside its edge ({@code t}: how chosen it looks). */
	static void chosen(Canvas c, float x, float y, float w, float h, float radius, float t) {
		if (t > 0.01F) {
			ring(c, x, y, w, h, radius, Math.max(1, c.px(d(2))), Palette.withAlpha(ACCENT, t));
		}
	}

	/** The 3 px halo a button wears under the pointer, outside its edge ({@code t}: how far it is in). */
	static void halo(Canvas c, float x, float y, float w, float h, float radius, float t, int argb) {
		if (t <= 0.01F) {
			return;
		}
		int b = Math.max(1, c.px(d(3)));
		int x0 = c.px(x);
		int y0 = c.px(y);
		c.ring(x0 - b, y0 - b, c.px(x + w) - x0 + 2 * b, c.px(y + h) - y0 + 2 * b, c.px(radius) + b, b, Palette.withAlpha(argb, (argb >>> 24) / 255.0F * t));
	}

	/**
	 * Dawn's buttons: primary (the accent, white text) or secondary (the text at 5%, grey text); the pointer brings a 3 px
	 * ring, the accent at 30% or the text at 3%.
	 */
	static void button(Canvas c, float x, float y, float w, float h, boolean primary, float hover, float press) {
		float rad = Math.min(radius(), h / 2);
		int fill = primary ? ACCENT : SOFT;
		fill = Palette.lerp(fill, 0xFF000000, press * 0.15F);
		box(c, x, y, w, h, rad, fill);
		halo(c, x, y, w, h, rad, hover, primary ? Palette.withAlpha(ACCENT, 0.30F) : Palette.withAlpha(TEXT, 0.03F));
	}

	/** A hairline of the reference (2 R) in whole window pixels, never under one. */
	static int edge() {
		return Math.max(1, Math.round(2 * factor));
	}

	// ---------- drawing helpers (pixel-snapped through the canvas) ----------

	/** A corner radius as drawn. */
	static float round(float radius) {
		return radius;
	}

	static void box(Canvas c, float x, float y, float w, float h, float radius, int argb) {
		radius = round(radius);
		int x0 = c.px(x);
		int y0 = c.px(y);
		c.roundRect(x0, y0, c.px(x + w) - x0, c.px(y + h) - y0, c.px(radius), argb);
	}

	/** The thin edge of the empty "Transparent" box, and the red line across it. */
	private static final int CLEAR_EDGE = 0xFFB4B4B4;
	private static final int SLASH = 0xFFE5484D;

	/**
	 * "Transparent": an empty box (a thin light edge, nothing inside, so whatever is behind shows through) with a thin red
	 * line from its bottom-left corner to its top-right one. The line's ends stop where the rounded corners begin, so it
	 * meets the edge cleanly at any size.
	 */
	static void transparent(Canvas c, float x, float y, float w, float h, float radius) {
		int x0 = c.px(x);
		int y0 = c.px(y);
		int pw = c.px(x + w) - x0;
		int ph = c.px(y + h) - y0;
		if (pw <= 0 || ph <= 0) {
			return;
		}
		int small = Math.min(pw, ph);
		int edge = Math.max(1, Math.round(small / 28.0F));
		int r = c.px(round(radius));
		c.ring(x0, y0, pw, ph, r, edge, CLEAR_EDGE);
		// The line: square steps of its thickness, from just inside one corner's curve to just inside the opposite one's.
		int t = Math.max(1, Math.round(small / 16.0F));
		int inset = edge + Math.round(r * 0.3F);
		int sx0 = x0 + inset;
		int sy0 = y0 + ph - inset - t;
		int sx1 = x0 + pw - inset - t;
		int sy1 = y0 + inset;
		int steps = Math.max(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
		for (int i = 0; i <= steps; i++) {
			int px = sx0 + Math.round((sx1 - sx0) * i / (float) Math.max(1, steps));
			int py = sy0 + Math.round((sy1 - sy0) * i / (float) Math.max(1, steps));
			c.fill(px, py, px + t, py + t, SLASH);
		}
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

	/** How far an appearance that began at {@code since} (milliseconds) has come, eased out: 0 to 1 over {@code millis}. */
	static float appear(long since, long millis) {
		float t = Math.clamp((Clock.millis() - since) / (float) millis, 0, 1);
		float u = 1 - t;
		return 1 - u * u * u;
	}

	/** Paints {@code child} as it appears ({@code k} from 0 to 1): faded in and risen from {@code rise} below. */
	static void appearing(Canvas c, com.flintclient.client.ui.kit.Node child, float k, float rise, java.util.function.BiConsumer<Canvas, com.flintclient.client.ui.kit.Node> paint) {
		if (k >= 1) {
			paint.accept(c, child);
			return;
		}
		float opacity = c.opacity();
		c.setOpacity(opacity * k);
		c.pushOffset(0, Math.round((1 - k) * rise * c.scale()));
		try {
			paint.accept(c, child);
		} finally {
			c.popOffset();
			c.setOpacity(opacity);
		}
	}

	/** Eases {@code current} towards {@code on} (the first frame snaps). */
	static float ease(float current, boolean on) {
		return current < 0 ? (on ? 1 : 0) : com.flintclient.client.ui.kit.Kit.approach(current, on ? 1 : 0, 0.05F);
	}

	static void filledIcon(Canvas c, com.flintclient.client.render.FilledIcon icon, float centerX, float centerY, float size, int argb) {
		int px = Math.max(1, c.px(size));
		c.filledIcon(icon, c.px(centerX) - px / 2, c.px(centerY) - px / 2, px, argb);
	}

	static void icon(Canvas c, Icon icon, float centerX, float centerY, float size, int argb) {
		int px = Math.max(1, c.px(size));
		c.icon(icon, c.px(centerX) - px / 2, c.px(centerY) - px / 2, px, argb);
	}
}

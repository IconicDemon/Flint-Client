package com.flintclient.client.control.editor;

import com.flintclient.client.control.menu.MenuScreen;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.Tokens2;
import com.flintclient.client.ui.kit.Type;
import java.util.Locale;

/**
 * What Right Shift opens on in a world: the HUD editor with this small cluster in the middle of the screen. The Flint
 * mark, then EDIT FLINT MODS (the module window) and WIDGETS (the palette of HUD widgets), then square shortcuts to the
 * window's other places (client settings, profiles, presets, waypoints, Flint Music) and a one-line hint. The
 * game, the HUD and the editing surface stay in view around it; it steps back while a module is dragged or the
 * selection lies under it, and H hides it with the other tools. Every button leads somewhere real.
 */
final class HubPanel extends Node {
	private static final float MARK = 22;
	private static final float BIG_H = 28;
	private static final float MODS_W = 132;
	private static final float WIDGETS_W = 78;
	private static final float SQUARE = 24;
	private static final float GAP = 6;
	private static final float FADE_TAU = 0.08F;

	private final HudEditor editor;
	private final TextLine wordmark = new TextLine(Type.WORDMARK, "FLINT");
	private final TextLine hintKey = new TextLine(Type.META, "");
	private final TextLine hintText = new TextLine(Type.META, "to close");
	private final Big mods;
	private final Big widgets;
	private final Square[] squares;
	private float fade = 1.0F;
	private float savedOpacity = 1.0F;

	HubPanel(HudEditor editor) {
		this.editor = editor;
		mods = add(new Big("EDIT FLINT MODS", Icon.GRID, true, () -> editor.openMenu(MenuScreen.Start.MODS)));
		widgets = add(new Big("WIDGETS", Icon.PLUS, false, editor::togglePalette));
		squares = new Square[] {
			add(new Square(Icon.GEAR, "Client settings", () -> editor.openMenu(MenuScreen.Start.SETTINGS))),
			add(new Square(Icon.USERS, "Profiles", () -> editor.openMenu(MenuScreen.Start.PROFILES))),
			add(new Square(Icon.SPARKLES, "Presets", () -> editor.openMenu(MenuScreen.Start.PRESETS))),
			add(new Square(Icon.FLAG, "Waypoints", () -> editor.openMenu(MenuScreen.Start.WAYPOINTS))),
			add(new Square(Icon.BRUSH, "Themes", () -> editor.openMenu(MenuScreen.Start.THEMES))),
			add(new Square(Icon.MUSIC, "Flint Music", () -> editor.openMenu(MenuScreen.Start.MUSIC)))};
	}

	/** The cluster's height: mark, buttons, shortcuts and hint. */
	static float clusterHeight() {
		return MARK + 12 + BIG_H + GAP + SQUARE + 10 + 12;
	}

	static float clusterWidth() {
		return MODS_W + GAP + WIDGETS_W;
	}

	/** Centred on a screen of {@code width × height} UI units. */
	void place(float width, float height) {
		float w = clusterWidth();
		float h = clusterHeight();
		setBounds(Math.round((width - w) / 2), Math.round((height - h) / 2), w, h);
	}

	@Override
	protected void layout() {
		float rowY = y + MARK + 12;
		mods.setBounds(x, rowY, MODS_W, BIG_H);
		widgets.setBounds(x + MODS_W + GAP, rowY, WIDGETS_W, BIG_H);
		float squaresW = squares.length * SQUARE + (squares.length - 1) * GAP;
		float sx = x + (w - squaresW) / 2;
		float sy = rowY + BIG_H + GAP;
		for (int i = 0; i < squares.length; i++) {
			squares[i].setBounds(sx + i * (SQUARE + GAP), sy, SQUARE, SQUARE);
		}
	}

	@Override
	protected void paint(Canvas c) {
		widgets.active = editor.paletteOpen();
		fade = Kit.approach(fade, editor.hubFaded() ? 0.22F : 1.0F, FADE_TAU);
		savedOpacity = c.opacity();
		c.setOpacity(savedOpacity * fade);
		// The mark and the wordmark, centred above the buttons.
		int markH = c.px(MARK);
		int markW = MaskAtlas.markWidth(markH);
		int wordW = c.width(wordmark);
		int gap = c.px(7);
		int left = c.px(x + w / 2) - (markW + gap + wordW) / 2;
		int top = c.px(y);
		c.mark(left, top, markH, 1);
		c.text(wordmark, left + markW + gap, c.baselineFor(wordmark, top + markH / 2), Tokens2.TEXT);
		// "RIGHT SHIFT to close" under the shortcuts.
		hintKey.set(MenuScreen.openKeyNameUpper());
		int keyW = c.width(hintKey) + c.px(8);
		int keyH = c.px(11);
		int hintW = keyW + c.px(5) + c.width(hintText);
		int hx = c.px(x + w / 2) - hintW / 2;
		int center = c.px(squares[0].y() + SQUARE + 10) + keyH / 2;
		c.roundRect(hx, center - keyH / 2, keyW, keyH, c.px(3), 0xC0131211);
		c.ring(hx, center - keyH / 2, keyW, keyH, c.px(3), 1, Tokens2.HAIRLINE_STRONG);
		c.text(hintKey, hx + c.px(4), c.baselineFor(hintKey, center), Tokens2.TEXT_DIM);
		c.text(hintText, hx + keyW + c.px(5), c.baselineFor(hintText, center), Tokens2.TEXT_DIM);
	}

	@Override
	protected void paintOver(Canvas c) {
		c.setOpacity(savedOpacity);
	}

	/** A wide button: ember (EDIT FLINT MODS) or dark (WIDGETS, ember-edged while the palette is open). */
	private static final class Big extends Pressable {
		private final TextLine label;
		private final Icon icon;
		private final boolean accent;
		boolean active;

		Big(String text, Icon icon, boolean accent, Runnable action) {
			super(action);
			this.label = new TextLine(Type.BUTTON, text);
			this.icon = icon;
			this.accent = accent;
			name(text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT));
		}

		@Override
		public String narration() {
			return accessibleName() + (accent ? "" : active ? ", open" : ", closed");
		}

		@Override
		protected void paint(Canvas c) {
			int x0 = px0(c);
			int y0 = py0(c);
			int pw = pxW(c);
			int ph = pxH(c);
			int r = c.px(7);
			int base = accent ? Palette.lerp(Tokens2.EMBER, Tokens2.EMBER_HOVER, hover) : Palette.lerp(0xF0131211, 0xF0201D1A, hover);
			int fill = Palette.lerp(base, 0xFF000000, press * 0.18F);
			c.roundRect(x0, y0 + c.px(2), pw, ph, r, 0x38000000);
			c.roundRect(x0, y0, pw, ph, r, fill);
			if (!accent) {
				int edge = active ? Palette.withAlpha(Tokens2.EMBER, 0.85F) : Palette.lerp(0x22FFFFFF, 0x3AFFFFFF, hover);
				c.ring(x0, y0, pw, ph, r, 1, edge);
			}
			int ink = accent ? Tokens2.INK : Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, Math.max(hover, active ? 1 : 0));
			int iconPx = c.px(9);
			int textW = c.width(label);
			int total = iconPx + c.px(6) + textW;
			int left = x0 + (pw - total) / 2;
			int center = y0 + ph / 2;
			c.icon(icon, left, center - iconPx / 2, iconPx, accent ? Tokens2.INK : active ? Tokens2.EMBER_TEXT : ink);
			c.text(label, left + iconPx + c.px(6), c.baselineFor(label, center), ink);
			Kit.focusRing(c, x, y, w, h, 7, focus);
		}
	}

	/** A square shortcut: an icon, its name as a tooltip. */
	private static final class Square extends Pressable {
		private final Icon icon;

		Square(Icon icon, String name, Runnable action) {
			super(action);
			this.icon = icon;
			name(name);
			tooltip(name);
		}

		@Override
		protected void paint(Canvas c) {
			int x0 = px0(c);
			int y0 = py0(c);
			int pw = pxW(c);
			int ph = pxH(c);
			int r = c.px(6);
			int fill = Palette.lerp(Palette.lerp(0xF0131211, 0xF0201D1A, hover), 0xFF000000, press * 0.2F);
			c.roundRect(x0, y0 + c.px(2), pw, ph, r, 0x30000000);
			c.roundRect(x0, y0, pw, ph, r, fill);
			c.ring(x0, y0, pw, ph, r, 1, Palette.lerp(0x1EFFFFFF, Palette.withAlpha(Tokens2.EMBER, 0.7F), hover));
			int iconPx = c.px(10);
			c.icon(icon, x0 + (pw - iconPx) / 2, y0 + (ph - iconPx) / 2, iconPx, Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, hover));
			Kit.focusRing(c, x, y, w, h, 6, focus);
		}
	}
}

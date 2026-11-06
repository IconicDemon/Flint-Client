package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.rshift.PageControls.TextButton;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Theme tab: the Flint app's themes (each card a small picture of the menu in its colours) and the menu's sounds. The whole window is the preview: a choice blends in at once and is saved in
 * {@code control-center.json}. Reset goes back to Flint's own look.
 */
final class ThemePage extends Node {
	private static final float PAD = 40;
	private static final float HEADER = 150;
	private static final float TITLE = 74;
	private static final float THEME_H = 330;

	private final Label title = new Label(Type.PAGE_TITLE, "Theme");
	private final Label about = new Label(Type.EMPTY_NOTE, "Choose how the menu looks and sounds. A choice shows at once.");
	private final Label themesTitle = new Label(Type.SECTION_TITLE, "Themes");
	private final Label soundTitle = new Label(Type.SECTION_TITLE, "Sounds");
	private final TextButton reset;
	private final List<ThemeCard> themes = new ArrayList<>();
	private final List<Node> soundRows = new ArrayList<>();

	ThemePage(Host host) {
		reset = add(new TextButton("Reset", Glyph.RESET, PageControls.Look.PLAIN, () -> set(MenuTheme.FLINT)));
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			themes.add(add(new ThemeCard(look)));
		}
		// The menu's own sounds (they set nothing else): only where the game plays them.
		if (MenuHost.menuSounds()) {
			soundRows.add(add(Rows.line(host, new Catalog.Row("Interface Sounds", "toggle", null, ControlSettings.SOUNDS, true))));
			soundRows.add(add(Rows.line(host, new Catalog.Row("Volume", "slider", null, ControlSettings.VOLUME, true))));
			soundRows.add(add(Rows.line(host, new Catalog.Row("Open and Close Sound", "toggle", null, GameOptions.MENU_SOUND, true))));
		}
	}

	private static MenuTheme theme() {
		return ControlState.settings().menuTheme();
	}

	private static void set(MenuTheme value) {
		ControlState.settings().setMenuTheme(value);
	}

	@Override
	protected void layout() {
		float left = x + r(PAD);
		float right = x + w - r(PAD);
		float width = right - left;
		float bh = r(TextButton.H);
		float rw = reset.prefWidth();
		reset.setBounds(right - MenuStyle.d(16) - rw, y + (r(HEADER) - bh) / 2, rw, bh);
		float gap = r(22);
		float at = y + r(HEADER) + r(TITLE);
		int n = themes.size();
		float tw = (width - (n - 1) * gap) / n;
		float th = Math.min(r(THEME_H), tw * 0.78F);
		for (int i = 0; i < n; i++) {
			themes.get(i).setBounds(left + i * (tw + gap), at, tw, th);
		}
		at += th + r(TITLE);
		boolean two = width >= r(1500);
		float colW = two ? (width - r(48)) / 2 : width;
		for (int i = 0; i < soundRows.size(); i++) {
			Node row = soundRows.get(i);
			float rh = row.prefHeight(colW);
			int col = two ? i % 2 : 0;
			int line = two ? i / 2 : i;
			row.setBounds(left + col * (colW + r(48)), at + r(10) + line * rh, colW, rh);
		}
	}

	@Override
	protected void paint(Canvas c) {
		float bar = r(HEADER);
		float left = x + r(PAD);
		float text = MenuStyle.titleBlock(c, left, y, x + w - r(PAD), bar);
		MenuStyle.fitted(c, title, text, y + bar / 2 - r(15), MenuStyle.WORDMARK, reset.x() - r(40) - text);
		MenuStyle.fitted(c, about, text, y + bar / 2 + r(22), MenuStyle.INFO, reset.x() - r(40) - text);
		c.fill(c.px(left), c.px(y + bar) - MenuStyle.edge(), c.px(x + w - r(PAD)), c.px(y + bar), MenuStyle.HEADER_RULE);
		if (!themes.isEmpty()) {
			MenuStyle.text(c, themesTitle, left, themes.get(0).y() - r(TITLE) / 2, MenuStyle.ROW_TEXT);
		}
		if (!soundRows.isEmpty()) {
			Node first = soundRows.get(0);
			float bottom = 0;
			for (Node row : soundRows) {
				bottom = Math.max(bottom, row.bottom());
			}
			MenuStyle.text(c, soundTitle, left, first.y() - r(10) - r(TITLE) / 2, MenuStyle.ROW_TEXT);
			MenuStyle.block(c, left, first.y() - r(10), x + w - r(PAD) - left, bottom - first.y() + r(20), MenuStyle.radius(), 0, 0);
		}
	}

	/** One of the app's themes: a small picture of the menu in its colours, its name and a few words; the one in use is ringed. */
	private static final class ThemeCard extends Pressable {
		private final MenuTheme.Look look;
		private final Label name;
		private final Label note;
		private final Label sample = new Label(Type.CARD_BUTTON, "Enabled");
		private float on = -1;

		ThemeCard(MenuTheme.Look look) {
			super(() -> set(theme().withLook(look)));
			this.look = look;
			this.name = new Label(Type.NAME, look.label());
			this.note = new Label(Type.CAPTION, look.note());
			name(look.label() + " theme");
		}

		@Override
		public String narration() {
			return accessibleName() + ", " + look.note() + (theme().look() == look ? ", in use" : "");
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, theme().look() == look);
			int bg = 0xFF000000 | look.background;
			int s1 = 0xFF000000 | look.surface1;
			int s2 = 0xFF000000 | look.surface2;
			int s3 = 0xFF000000 | look.surface3;
			int s4 = 0xFF000000 | look.surface4;
			int ink = 0xFF000000 | look.ink;
			int accent = 0xFF000000 | look.accent;
			float rad = MenuStyle.radius();
			int edge = Palette.lerp(Palette.lerp(s4, Palette.lerp(s4, ink, 0.25F), hover), accent, on);
			MenuStyle.framed(c, x, y, w, h, rad, s1, edge);
			if (on > 0.01F) {
				MenuStyle.ring(c, x, y, w, h, rad, Math.max(2, 2 * MenuStyle.edge()), Palette.withAlpha(accent, on));
			}
			// A small picture of the menu: the page, an open tab and two closed ones, and a mod card switched on.
			float pad = r(18);
			float px = x + pad;
			float py = y + pad;
			float pw = w - 2 * pad;
			float ph = h * 0.58F;
			MenuStyle.box(c, px, py, pw, ph, r(12), bg);
			float tab = ph * 0.18F;
			MenuStyle.box(c, px + r(14), py + r(14), tab * 2.6F, tab, r(5), accent);
			MenuStyle.box(c, px + r(14) + tab * 2.6F + r(8), py + r(14), tab, tab, r(5), s3);
			MenuStyle.box(c, px + r(14) + tab * 3.6F + r(16), py + r(14), tab, tab, r(5), s3);
			float cardY = py + r(14) + tab + r(12);
			float cardH = py + ph - r(14) - cardY;
			float cardW = (pw - r(28) - r(10)) / 2;
			for (int i = 0; i < 2; i++) {
				float cx = px + r(14) + i * (cardW + r(10));
				MenuStyle.framed(c, cx, cardY, cardW, cardH, r(8), s2, s3);
				MenuStyle.box(c, cx + r(10), cardY + r(10), cardW * 0.55F, r(8), r(4), Palette.lerp(s2, ink, i == 0 ? 0.85F : 0.45F));
				int button = i == 0 ? Palette.lerp(s2, accent, 0.28F) : s4;
				MenuStyle.box(c, cx + r(10), cardY + cardH - r(10) - cardH * 0.26F, cardW - r(20), cardH * 0.26F, r(5), button);
			}
			if (cardW > sample.width(c) + r(30)) {
				MenuStyle.centered(c, sample, px + r(14) + cardW / 2, cardY + cardH - r(10) - cardH * 0.13F, 0xFF000000 | look.accentText, cardW - r(24));
			}
			float textY = py + ph + (y + h - py - ph) / 2;
			MenuStyle.fitted(c, name, x + pad, textY - r(18), ink, w - 2 * pad - r(40));
			MenuStyle.fitted(c, note, x + pad, textY + r(22), Palette.lerp(s2, ink, 0.55F), w - 2 * pad);
			if (on > 0.01F) {
				float d = r(34);
				float dx = x + w - pad - d;
				float dy = textY - r(18) - d / 2;
				MenuStyle.box(c, dx, dy, d, d, d / 2, Palette.withAlpha(accent, on));
				Glyph.CHECK.draw(c, dx + d / 2, dy + d / 2, r(22), Palette.withAlpha(0xFF000000 | look.accentInk, on));
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}

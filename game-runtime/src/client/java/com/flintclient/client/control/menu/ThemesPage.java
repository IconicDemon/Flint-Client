package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.hud.render.HudRenderer;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Labelled;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.Picture;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * THEMES (the brush in the window's header): the menu's look and the HUD's look, both shown as they change.
 *
 * <p>On the left, "Menu": six preset tiles (each drawn in its own colours), the accent (the colour picker, any colour by
 * hex), the surface family, corner roundness and how dark the game gets behind the window, and Reset to Flint. Every
 * change applies to the whole window at once (the window is its own preview) and is saved with Flint's settings, the same
 * in every profile. Then "HUD: this profile": the style, text and accent colours and labels the profile's widgets share
 * (a widget with its own look keeps it), saved with the profile.
 *
 * <p>On the right, a preview: a small model of the menu in the current colours (a tab, chips, a card on and one off, a
 * switch and a slider), and under it three real widgets drawn by the HUD renderer with sample values in the profile's look.
 */
final class ThemesPage extends Node {
	private static final float PREVIEW_SHARE = 0.40F;

	private final ScrollView scroll;
	private final Column column = new Column();
	private final Preview preview;

	ThemesPage(MenuWindow window) {
		ControlSettings s = ControlState.settings();
		Section menu = column.add(new Section("Menu"));
		menu.addRow(new PresetRow());
		PageControls.ColorSwatch[] accent = new PageControls.ColorSwatch[1];
		accent[0] = new PageControls.ColorSwatch(() -> s.menuTheme().accentRgb(), () -> window.openColorPicker(accent[0], "Accent",
			MenuTheme.FLINT.accentRgb(), () -> s.menuTheme().accentRgb(), rgb -> s.setMenuTheme(s.menuTheme().withAccent(rgb))));
		menu.row("Accent", "Selected tabs, primary buttons, switches and focus", accent[0]);
		menu.row("Surface", "The colour family of the window and its panels", new PageControls.Segmented(labels(MenuTheme.Surface.values()),
			() -> s.menuTheme().surface().ordinal(), i -> s.setMenuTheme(s.menuTheme().withSurface(MenuTheme.Surface.values()[i]))));
		menu.row("Roundness", "Corners of the window, cards and buttons", new PageControls.Slider(MenuTheme.MIN_ROUNDNESS, MenuTheme.MAX_ROUNDNESS, 10,
			"%", () -> s.menuTheme().roundness(), v -> s.setMenuTheme(s.menuTheme().withRoundness(v))));
		menu.row("Background dim", "How dark the game gets behind the window", new PageControls.Slider(MenuTheme.MIN_DIM, MenuTheme.MAX_DIM, 5, "%",
			() -> s.menuTheme().dim(), v -> s.setMenuTheme(s.menuTheme().withDim(v))));
		menu.row("Back to Flint", "Flint's own look: ember on warm charcoal", new PageControls.TextButton("Reset", Icon.RESET, PageControls.Look.PLAIN,
			() -> s.setMenuTheme(MenuTheme.FLINT)));
		Section hud = column.add(new Section("HUD: this profile"));
		hud.row("Style", "How widgets are drawn, unless a widget has its own", new PageControls.Segmented(labels(HudThemeId.values()),
			() -> hudTheme().ordinal(), i -> setLook(look().withTheme(HudThemeId.values()[i]))));
		hud.row("Text color", null, new PageControls.Stepper(labels(HudColor.values()), () -> color(false).ordinal(),
			i -> setLook(look().withTextColor(HudColor.values()[i]))));
		hud.row("Accent color", "Labels, units and pressed keys; Default is the style's own", new PageControls.Stepper(labels(HudColor.values()),
			() -> color(true).ordinal(), i -> setLook(look().withAccentColor(HudColor.values()[i]))));
		hud.row("Labels", "Each widget's name before its value", new PageControls.Switch(ThemesPage::labelsShown,
			() -> setLook(look().withShowLabel(!labelsShown()))));
		scroll = add(new ScrollView(column));
		preview = add(new Preview());
	}

	private static <E extends Enum<E> & Labelled> String[] labels(E[] values) {
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = values[i].label();
		}
		return out;
	}

	private static Appearance look() {
		return ControlState.layout().appearance();
	}

	private static HudThemeId hudTheme() {
		HudThemeId theme = look().theme();
		return theme == null ? HudThemeId.FLINT : theme;
	}

	private static HudColor color(boolean accent) {
		HudColor color = accent ? look().accentColor() : look().textColor();
		return color == null ? HudColor.DEFAULT : color;
	}

	private static boolean labelsShown() {
		Boolean shown = look().showLabel();
		return shown == null || shown;
	}

	/** The profile's shared look (saved with the profile by the menu's usual saving). */
	private static void setLook(Appearance look) {
		ControlState.layout().setAppearance(look);
	}

	@Override
	protected void layout() {
		float previewW = Math.round(w * PREVIEW_SHARE);
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(120));
		scroll.setBounds(x, y, w - previewW - r(32), h);
		preview.setBounds(x + w - previewW, y, previewW, h);
	}

	// ---------- left column ----------

	private static final class Column extends Node {
		@Override
		public float prefHeight(float width) {
			float total = 0;
			for (int i = 0; i < childCount(); i++) {
				total += child(i).prefHeight(width) + r(24);
			}
			return total;
		}

		@Override
		protected void layout() {
			float at = y;
			for (int i = 0; i < childCount(); i++) {
				Node section = child(i);
				float sh = section.prefHeight(w);
				section.setBounds(x, at, w, sh);
				at += sh + r(24);
			}
		}
	}

	/** A titled card of rows. */
	private static final class Section extends Node {
		private static final float HEAD = 60;
		private final Label title;
		private final List<Node> rows = new ArrayList<>();

		Section(String title) {
			this.title = new Label(Type.SECTION_TITLE, title);
		}

		void addRow(Node row) {
			rows.add(add(row));
		}

		void row(String label, @Nullable String note, Node control) {
			addRow(new Row(label, note, control));
		}

		@Override
		public float prefHeight(float width) {
			float total = r(HEAD) + r(12);
			for (Node row : rows) {
				total += row.prefHeight(width);
			}
			return total;
		}

		@Override
		protected void layout() {
			float at = y + r(HEAD) + r(6);
			for (Node row : rows) {
				float rh = row.prefHeight(w);
				row.setBounds(x, at, w, rh);
				at += rh;
			}
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.text(c, title, x + r(16), y + r(HEAD) / 2, MenuStyle.ROW_TEXT);
			MenuStyle.framed(c, x, y + r(HEAD), w, h - r(HEAD), r(20), MenuStyle.SECTION_CARD, MenuStyle.SECTION_EDGE);
		}
	}

	/** A label (and note) on the left, the control on the right. */
	private static final class Row extends Node {
		private final Label label;
		private final Label note;
		private final Node control;

		Row(String label, @Nullable String note, Node control) {
			this.label = new Label(Type.ROW_LABEL, label);
			this.note = new Label(Type.ROW_NOTE, note == null ? "" : note);
			this.control = add(control);
			control.name(label);
		}

		@Override
		public float prefHeight(float width) {
			return r(note.text().isEmpty() ? 80 : 104);
		}

		@Override
		protected void layout() {
			float cw = control.prefWidth();
			float ch = control instanceof PageControls.Switch ? r(PageControls.Switch.H) : control instanceof PageControls.Slider
				? r(PageControls.Slider.H) : control instanceof PageControls.ColorSwatch ? r(PageControls.ColorSwatch.H)
				: control instanceof PageControls.TextButton ? r(PageControls.TextButton.H) : r(52);
			control.setBounds(x + w - r(28) - cw, y + (h - ch) / 2, cw, ch);
		}

		@Override
		protected void paint(Canvas c) {
			float left = x + r(28);
			float room = control.x() - r(24) - left;
			float cy = y + h / 2;
			boolean hasNote = !note.text().isEmpty();
			MenuStyle.fitted(c, label, left, hasNote ? cy - r(14) : cy, MenuStyle.ROW_TEXT, room);
			if (hasNote) {
				MenuStyle.fitted(c, note, left, cy + r(18), MenuStyle.LABEL, room);
			}
		}
	}

	/** The six preset tiles, three to a row. */
	private static final class PresetRow extends Node {
		private static final float TILE_H = 118;

		PresetRow() {
			for (MenuTheme.Preset preset : MenuTheme.PRESETS) {
				add(new Tile(preset));
			}
		}

		@Override
		public float prefHeight(float width) {
			int rows = (childCount() + 2) / 3;
			return r(20) + rows * r(TILE_H) + (rows - 1) * r(16) + r(20);
		}

		@Override
		protected void layout() {
			float inner = w - 2 * r(28);
			float tw = (inner - 2 * r(16)) / 3;
			for (int i = 0; i < childCount(); i++) {
				child(i).setBounds(x + r(28) + (i % 3) * (tw + r(16)), y + r(20) + (i / 3) * (r(TILE_H) + r(16)), tw, r(TILE_H));
			}
		}
	}

	/** One preset: drawn in its own window, panel and accent colours, its name, a ring when it is the theme in use. */
	private static final class Tile extends Pressable {
		private final MenuTheme.Preset preset;
		private final Label name;
		private final int window;
		private final int panel;
		private final int ink;

		Tile(MenuTheme.Preset preset) {
			super(() -> ControlState.settings().setMenuTheme(preset.theme()));
			this.preset = preset;
			this.name = new Label(Type.PAGE_BUTTON, preset.name());
			MenuTheme t = preset.theme();
			this.window = t.tint(0xFF211E1B);
			this.panel = t.tint(0xFF2F2C27);
			this.ink = t.tint(0xFFF1EBE3);
			name(preset.name() + " theme");
		}

		@Override
		public String narration() {
			return accessibleName() + (ControlState.settings().menuTheme().equals(preset.theme()) ? ", in use" : "");
		}

		@Override
		protected void paint(Canvas c) {
			boolean chosen = ControlState.settings().menuTheme().equals(preset.theme());
			float rad = r(16) * preset.theme().roundness() / 100F;
			int x0 = c.px(x);
			int y0 = c.px(y);
			int pw = c.px(x + w) - x0;
			int ph = c.px(y + h) - y0;
			c.roundRect(x0, y0, pw, ph, c.px(rad), Palette.lerp(window, 0xFFFFFFFF, hover * 0.04F));
			int accent = 0xFF000000 | preset.theme().accentRgb();
			// A tiny card with an accent bar and a dot: the theme in miniature.
			MenuStyle.box(c, x + r(16), y + r(16), w * 0.42F, r(44), r(8) * preset.theme().roundness() / 100F, panel);
			MenuStyle.box(c, x + r(16), y + r(16), w * 0.16F, r(44), r(8) * preset.theme().roundness() / 100F, accent);
			MenuStyle.box(c, x + w - r(16) - r(24), y + r(26), r(24), r(24), r(12), accent);
			MenuStyle.fitted(c, name, x + r(18), y + h - r(28), ink, w - r(36));
			int edge = chosen ? MenuStyle.ACCENT : Palette.lerp(0x22FFFFFF, 0x44FFFFFF, hover);
			c.ring(x0, y0, pw, ph, c.px(rad), chosen ? Math.max(2, MenuStyle.edge()) : MenuStyle.edge(), edge);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	// ---------- preview ----------

	/** A model of the menu in the current colours, then real widgets in the profile's HUD look. */
	private static final class Preview extends Node {
		private final Label title = new Label(Type.SECTION_TITLE, "Preview");
		private final Label tab = new Label(Type.TAB, "MODS");
		private final Label tab2 = new Label(Type.TAB, "SETTINGS");
		private final Label chip = new Label(Type.CHIP, "ALL");
		private final Label chip2 = new Label(Type.CHIP, "HUD");
		private final Label on = new Label(Type.CARD_BUTTON, "ENABLED");
		private final Label off = new Label(Type.CARD_BUTTON, "DISABLED");
		private final Label cardA = new Label(Type.NAME, "Keystrokes");
		private final Label cardB = new Label(Type.NAME, "Zoom");
		private final Label hudCaption = new Label(Type.CAPTION, "Your HUD with sample values");
		private static final String[] SAMPLES = {"fps", "coordinates", "keystrokes"};

		@Override
		protected void paint(Canvas c) {
			MenuStyle.text(c, title, x + r(16), y + r(30), MenuStyle.ROW_TEXT);
			// The menu in miniature.
			float my = y + r(60);
			float mh = Math.min(r(520), h * 0.5F);
			MenuStyle.framed(c, x, my, w, mh, r(22), MenuStyle.WINDOW, MenuStyle.WINDOW_EDGE);
			MenuStyle.box(c, x + r(2), my + r(2), w - r(4), r(70), r(20), MenuStyle.HEADER);
			c.mark(c.px(x + r(24)), c.px(my + r(35)) - c.px(r(36)) / 2, c.px(r(36)), 1);
			float tx = x + r(80);
			float tw = tab.width(c) + r(40);
			MenuStyle.framed(c, tx, my + r(16), tw, r(40), r(10), MenuStyle.TAB_SELECTED, MenuStyle.TAB_SELECTED_EDGE);
			MenuStyle.text(c, tab, tx + r(20), my + r(36), MenuStyle.TAB_TEXT_SELECTED);
			MenuStyle.text(c, tab2, tx + tw + r(20), my + r(36), MenuStyle.TAB_TEXT);
			float cy = my + r(96);
			float cw = chip.width(c) + r(36);
			MenuStyle.box(c, x + r(24), cy, cw, r(40), r(12), MenuStyle.CHIP_SELECTED);
			MenuStyle.text(c, chip, x + r(42), cy + r(20), MenuStyle.CHIP_SELECTED_TEXT);
			MenuStyle.box(c, x + r(24) + cw + r(12), cy, chip2.width(c) + r(36), r(40), r(12), MenuStyle.CHIP);
			MenuStyle.text(c, chip2, x + r(42) + cw + r(12), cy + r(20), MenuStyle.CHIP_TEXT);
			float cardY = cy + r(64);
			float cardW = (w - r(48) - r(20)) / 2;
			float cardH = Math.max(r(120), my + mh - r(24) - cardY);
			card(c, x + r(24), cardY, cardW, cardH, cardA, true);
			card(c, x + r(24) + cardW + r(20), cardY, cardW, cardH, cardB, false);
			// The HUD, drawn for real with the profile's look over the sample scene.
			float hy = my + mh + r(28);
			float hh = y + h - hy - r(50);
			if (hh > r(80)) {
				int x0 = c.px(x);
				int y0 = c.px(hy);
				int pw = c.px(x + w) - x0;
				int ph = c.px(hy + hh) - y0;
				Picture.Level level = ModulePage.PICTURE.best(pw, ph);
				if (level != null) {
					c.picture(level, x0, y0, pw, ph, c.px(MenuStyle.round(r(18))), MenuStyle.PREVIEW_FRAME);
				} else {
					c.roundRect(x0, y0, pw, ph, c.px(MenuStyle.round(r(18))), 0xFF2A2724);
				}
				int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
				int third = pw / SAMPLES.length;
				for (int i = 0; i < SAMPLES.length; i++) {
					HudRenderer.drawSample(c, ControlState.layout(), SAMPLES[i], x0 + i * third, y0, third, ph, guiScale);
				}
				MenuStyle.fitted(c, hudCaption, x + r(6), hy + hh + r(26), MenuStyle.LABEL, w - r(12));
			}
		}

		private void card(Canvas c, float cx, float cy, float cw, float ch, Label name, boolean enabled) {
			MenuStyle.framed(c, cx, cy, cw, ch, r(20), MenuStyle.CARD, MenuStyle.CARD_EDGE);
			MenuStyle.centered(c, name, cx + cw / 2, cy + r(40), enabled ? MenuStyle.NAME_ON : MenuStyle.NAME_OFF, cw - r(24));
			float by = cy + ch - r(18) - r(48);
			MenuStyle.box(c, cx + r(14), by, cw - r(28), r(48), r(12), enabled ? MenuStyle.ENABLED : MenuStyle.DISABLED);
			if (enabled) {
				MenuStyle.ring(c, cx + r(14), by, cw - r(28), r(48), r(12), MenuStyle.edge(), MenuStyle.ENABLED_EDGE);
			}
			MenuStyle.centered(c, enabled ? on : off, cx + cw / 2, by + r(24), enabled ? MenuStyle.ENABLED_TEXT : MenuStyle.DISABLED_TEXT, cw - r(40));
			// A switch, on for the enabled card.
			float sw = r(64);
			float sh = r(34);
			float sx = cx + cw / 2 - sw / 2;
			float sy = cy + r(78);
			MenuStyle.box(c, sx, sy, sw, sh, sh / 2, enabled ? MenuStyle.ACCENT : MenuStyle.TRACK_OFF);
			float knob = sh - r(8);
			MenuStyle.box(c, enabled ? sx + sw - r(4) - knob : sx + r(4), sy + r(4), knob, knob, knob / 2, enabled ? MenuStyle.ON_ACCENT
				: MenuStyle.KNOB_OFF);
		}
	}
}

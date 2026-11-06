package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The HUD page's own controls over the editing surface, laid out as the reference's: in the middle Flint's logo, EDIT
 * FLINT MODS and HUDS, with a row of shortcut tiles under them; "?" at the bottom left opens the list of controls; the
 * HUDs palette docks at the right. The middle steps back while a mod is dragged. Only tiles for things Flint has are
 * shown (Minecraft's settings, Flint's pack organizer, screenshots, waypoints, the game folder).
 */
final class HudPage extends Node {
	private final HudPageScreen screen;
	private final Hub hub;
	private final Legend legend;
	private final Huds huds;
	private final Inspector inspector;

	HudPage(HudPageScreen screen) {
		this.screen = screen;
		hub = add(new Hub());
		legend = add(new Legend());
		inspector = add(new Inspector());
		huds = add(new Huds());
		huds.setVisible(false);
	}

	private void toggleHuds() {
		if (huds.visible()) {
			huds.close();
		} else {
			huds.open();
		}
	}

	/** Escape: the palette, then the controls panel; false when neither was open. */
	boolean escape() {
		if (huds.visible()) {
			huds.close();
			return true;
		}
		if (legend.open) {
			legend.open = false;
			legend.invalidate();
			return true;
		}
		return false;
	}

	/** The inspector shows while one HUD mod is selected (decided here: a hidden node isn't drawn, so it can't decide itself). */
	@Override
	protected void paint(Canvas c) {
		boolean show = screen.editor().single() >= 0;
		if (show != inspector.visible()) {
			inspector.setVisible(show);
		}
	}

	@Override
	protected void layout() {
		hub.setBounds(x, y, w, h);
		legend.setBounds(x, y, w, h);
		float pw = Math.min(r(640), w * 0.4F);
		huds.setBounds(x + w - pw - r(40), y + r(40), pw, h - 2 * r(40));
		float iw = Math.min(r(1240), w - 2 * r(40));
		// At the top, clear of Minecraft's hotbar and bars at the bottom.
		inspector.setBounds(x + (w - iw) / 2, y + r(36), iw, r(96));
	}

	// ---------- the inspector ----------

	/**
	 * Over the HUD while one HUD mod is selected: its name and boxes to type its exact place (X and Y, in GUI pixels from the
	 * top left) and size (0.25 to 4). Enter applies a box (one undo step); the boxes follow the mod when it is dragged.
	 */
	private final class Inspector extends Node {
		private final Label name = new Label(Type.CHIP, "");
		private final Label xLabel = new Label(Type.CHIP, "X");
		private final Label yLabel = new Label(Type.CHIP, "Y");
		private final Label sLabel = new Label(Type.CHIP, "Scale");
		private final TextField xField = add(field("X position"));
		private final TextField yField = add(field("Y position"));
		private final TextField sField = add(field("Scale"));
		private @Nullable FontFace face;
		private int shownFor = -1;

		Inspector() {
			xField.onSubmit(text -> apply());
			yField.onSubmit(text -> apply());
			sField.onSubmit(text -> apply());
		}

		private static TextField field(String what) {
			TextField f = new TextField("", 6);
			f.name(what);
			return f;
		}

		private void apply() {
			HudEditorAccess e = editor();
			int i = e.single();
			if (i < 0) {
				return;
			}
			Float px = number(xField.text());
			Float py = number(yField.text());
			if (px != null && py != null) {
				e.placeAt(i, px, py);
			}
			Float s = number(sField.text());
			if (s != null) {
				e.sizeTo(i, s);
			}
			shownFor = -2;
		}

		private static @Nullable Float number(String text) {
			try {
				String t = text.trim().replace("×", "").replace("x", "").replace(",", ".");
				float v = Float.parseFloat(t);
				return Float.isFinite(v) ? v : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}

		private HudEditorAccess editor() {
			return new HudEditorAccess(screen.editor());
		}

		@Override
		public boolean interactive() {
			return visible();
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			return button == Kit.LEFT_BUTTON;
		}

		@Override
		protected void layout() {
			FontFace now = Type.SEARCH.face();
			if (now != face) {
				face = now;
				TextField.Look look = new TextField.Look(now, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
					r(12), r(16), 0, 0, MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT);
				xField.look(look);
				yField.look(look);
				sField.look(look);
			}
			float fh = r(60);
			float fw = r(150);
			float fy = y + (h - fh) / 2;
			float right = x + w - r(28);
			sField.setBounds(right - fw, fy, fw, fh);
			yField.setBounds(sField.x() - r(120) - fw, fy, fw, fh);
			xField.setBounds(yField.x() - r(70) - fw, fy, fw, fh);
		}

		@Override
		protected void paint(Canvas c) {
			HudEditorAccess e = editor();
			int i = e.single();
			if (i < 0) {
				return;
			}
			// The boxes show the mod's place and size, unless one is being typed in.
			Node focus = screen.focused();
			boolean typing = focus == xField || focus == yField || focus == sField;
			if (!typing) {
				String sx = Integer.toString(Math.round(e.left(i)));
				String sy = Integer.toString(Math.round(e.top(i)));
				String ss = com.flintclient.client.control.editor.HudEditor.scaleText(e.scale(i));
				if (!sx.equals(xField.text())) {
					xField.setText(sx);
				}
				if (!sy.equals(yField.text())) {
					yField.setText(sy);
				}
				if (!ss.equals(sField.text())) {
					sField.setText(ss);
				}
			}
			if (shownFor != i) {
				shownFor = i;
				name.set(e.name(i));
			}
			MenuStyle.block(c, x, y, w, h, MenuStyle.radius(), 0, 0);
			float cy = y + h / 2;
			MenuStyle.fitted(c, name, x + r(28), cy, MenuStyle.TAB_TEXT_SELECTED, xField.x() - r(90) - x - r(28));
			MenuStyle.text(c, xLabel, xField.x() - r(40), cy, MenuStyle.LABEL);
			MenuStyle.text(c, yLabel, yField.x() - r(40), cy, MenuStyle.LABEL);
			MenuStyle.text(c, sLabel, sField.x() - r(96), cy, MenuStyle.LABEL);
		}
	}

	/** The editor's typed-value calls, by name (the editor lives in another package). */
	private record HudEditorAccess(com.flintclient.client.control.editor.HudEditor editor) {
		int single() {
			return editor.single();
		}

		String name(int i) {
			com.flintclient.client.control.model.ModuleDescriptor module = ModuleRegistry.all().get(i);
			Catalog.Mod mod = Catalog.get().modFor(module.id());
			return mod != null && mod.module() == module ? mod.name() : editor.moduleName(i);
		}

		float left(int i) {
			return editor.left(i);
		}

		float top(int i) {
			return editor.top(i);
		}

		float scale(int i) {
			return editor.scale(i);
		}

		void placeAt(int i, float left, float top) {
			editor.placeAt(i, left, top);
		}

		void sizeTo(int i, float scale) {
			editor.sizeTo(i, scale);
		}
	}

	// ---------- the middle ----------

	/** The logo, EDIT FLINT MODS and HUDS, and the shortcut tiles under them. */
	private final class Hub extends Node {
		/** The page comes in over this long: everything fades in together, rising a few pixels. */
		private static final float RISE_SECONDS = 0.2F;
		private final Big mods;
		private final Big widgets;
		private final List<Tile> tiles = new ArrayList<>();
		private float faded = -1;
		/** When the page opened, for its fade in. */
		private final long openedAt = Clock.millis();

		Hub() {
			mods = add(new Big("EDIT FLINT MODS", Glyph.GRID, true, screen::openWindow));
			widgets = add(new Big("HUDS", Glyph.PLUS, false, HudPage.this::toggleHuds));
			tiles.add(add(new Tile("MC Settings", Glyph.GEAR, () -> screen.openScreen(MenuHost.optionsScreen(screen)))));
			tiles.add(add(new Tile("Resource Packs", Glyph.LAYERS, () -> {
				if (MenuHost.packOrganizer()) {
					ModMenuScreen.showModule(com.flintclient.client.control.model.mods.UtilityMods.PackOrganizer.ID);
					screen.openWindow();
				} else {
					Object packs = MenuHost.packsScreen(screen);
					if (packs != null) {
						screen.openScreen(packs);
					}
				}
			})));
			tiles.add(add(new Tile("Screenshots", Glyph.CAMERA, () -> {
				Object shots = MenuHost.screenshotsScreen(screen);
				if (shots != null) {
					screen.openScreen(shots);
				} else {
					MenuHost.openFolder("screenshots");
				}
			})));
			tiles.add(add(new Tile("Waypoints", Glyph.FLAG, () -> {
				ModMenuScreen.showWaypoints();
				screen.openWindow();
			})));
			tiles.add(add(new Tile("Minecraft Folder", Glyph.FOLDER, () -> MenuHost.openGameFolder())));
		}

		@Override
		protected void layout() {
			float cx = x + w / 2;
			float cy = y + h / 2;
			float bw = r(700);
			float bh = r(118);
			mods.setBounds(cx - bw / 2, cy - bh - r(12), bw, bh);
			widgets.setBounds(cx - bw / 2, cy + r(12), bw, bh);
			float tw = r(180);
			// Room for each name under its tile ("MINECRAFT FOLDER").
			float gap = r(110);
			float total = tiles.size() * tw + (tiles.size() - 1) * gap;
			float ty = cy + r(12) + bh + r(80);
			for (int i = 0; i < tiles.size(); i++) {
				tiles.get(i).setBounds(cx - total / 2 + i * (tw + gap), ty, tw, tw);
			}
		}

		@Override
		protected void paint(Canvas c) {
			// Steps back while a mod is dragged or a box drawn, so the HUD under it can be seen.
			faded = MenuStyle.ease(faded, screen.editor().busy());
			// A soft dark fall from the top and bottom edges, so the page reads over any world (it leaves the middle clear).
			float shade = 1 - 0.8F * faded;
			c.gradient(c.px(x), c.px(y), c.px(x + w), c.px(y + h * 0.42F), Palette.withAlpha(0xFF000000, 0.62F * shade), 0x00000000);
			c.gradient(c.px(x), c.px(y + h * 0.58F), c.px(x + w), c.px(y + h), 0x00000000, Palette.withAlpha(0xFF000000, 0.55F * shade));
			float cx = x + w / 2;
			int markH = Math.max(1, c.px(r(190)));
			float top = mods.y() - r(70) - r(190);
			float e = easeOut(Math.clamp(since() / RISE_SECONDS, 0, 1));
			int rise = Math.round(c.px(MenuStyle.d(8)) * (1 - e));
			c.mark(c.px(cx) - com.flintclient.client.render.MaskAtlas.markWidth(markH) / 2, c.px(top) + rise, markH, e * (1.0F - 0.8F * faded));
		}

		/** Seconds since the page opened; far along with animations off or reduced motion, so everything is in place. */
		private float since() {
			if (Kit.reduced() || !ControlState.settings().animations()) {
				return 10;
			}
			return (Clock.millis() - openedAt) / 1000.0F;
		}

		@Override
		protected void paintChildren(Canvas c) {
			float opacity = c.opacity();
			float base = opacity * (1 - 0.8F * Math.max(0, faded));
			float t = since();
			try {
				rise(c, mods, base, t);
				rise(c, widgets, base, t);
				for (int i = 0; i < tiles.size(); i++) {
					rise(c, tiles.get(i), base, t);
				}
			} finally {
				c.setOpacity(opacity);
			}
		}

		/** One button or tile, {@code t} seconds into its own rise: fading in as it comes up into place. */
		private void rise(Canvas c, Node child, float opacity, float t) {
			float e = easeOut(Math.clamp(t / RISE_SECONDS, 0, 1));
			if (e <= 0) {
				return;
			}
			c.setOpacity(opacity * e);
			int dy = Math.round(c.px(MenuStyle.d(8)) * (1 - e));
			c.pushOffset(0, dy);
			try {
				paintChild(c, child);
			} finally {
				c.popOffset();
			}
		}

		private static float easeOut(float x) {
			float u = 1 - x;
			return 1 - u * u * u;
		}

	}

	/** EDIT FLINT MODS (ember) or HUDS: a wide button with an icon before its label. */
	private static final class Big extends Pressable {
		private final Label label;
		private final Glyph icon;
		private final boolean primary;

		Big(String text, Glyph icon, boolean primary, Runnable action) {
			super(action);
			this.label = new Label(Type.HUB_BUTTON, text);
			this.icon = icon;
			this.primary = primary;
			name(text.charAt(0) + text.substring(1).toLowerCase(Locale.ROOT));
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			if (primary) {
				MenuStyle.button(c, x, y, w, h, true, hover, press);
			} else {
				MenuStyle.block(c, x, y, w, h, rad, hover, 0);
			}
			int ink = primary ? MenuStyle.ON_ACCENT : MenuStyle.ROW_TEXT;
			float total = r(42) + r(20) + label.width(c);
			float left = x + (w - total) / 2;
			icon.draw(c, left + r(21), y + h / 2, r(42), ink);
			MenuStyle.text(c, label, left + r(42) + r(20), y + h / 2, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A shortcut tile: a square with an icon, its name under it. */
	private static final class Tile extends Pressable {
		private final Label label;
		private final Glyph icon;

		Tile(String text, Glyph icon, Runnable action) {
			super(action);
			this.label = new Label(Type.BADGE, text.toUpperCase(Locale.ROOT));
			this.icon = icon;
			name(text);
			tooltip(text);
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			MenuStyle.block(c, x, y, w, h, rad, hover, 0);
			icon.draw(c, x + w / 2, y + h / 2, r(56), Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover));
			// On a dark pill, so the name reads over any world (snow, sky).
			float lw = Math.min(label.width(c), w + r(90)) + r(24);
			MenuStyle.box(c, x + w / 2 - lw / 2, y + h + r(30) - r(19), lw, r(38), MenuStyle.d(4), Palette.withAlpha(0xFF000000, 0.75F));
			MenuStyle.centered(c, label, x + w / 2, y + h + r(30), MenuStyle.TAB_TEXT_SELECTED, w + r(90));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	// ---------- "?" ----------

	/** "?" at the bottom left; open, the controls of the HUD page above it (every one of them works). */
	private final class Legend extends Node {
		private static final String[][] ROWS = {
			{"Mouse 1", "Hold: select and move a mod"},
			{"Shift + Mouse 1", "Add a mod to the selection"},
			{"Mouse 2 + Drag", "Select several mods"},
			{"Scroll", "Scale the mod under the pointer"},
			{"Ctrl + Arrow", "Large nudge (10)"},
			{"Shift + Arrow", "Precise nudge (½)"},
			{"Arrow", "Move the selection (1)"},
			{"Tab", "Select the next mod"},
			{"Space", "Reset the selection's size"},
			{"Alt", "Hold while dragging: no snapping"},
			{"Ctrl + Z", "Undo (Ctrl + Shift + Z: redo)"}};
		private final Label title = new Label(Type.CHIP, "SHORTCUTS & MOVEMENT");
		private final Label[] keys = new Label[ROWS.length];
		private final Label[] actions = new Label[ROWS.length];
		private final Parts.Square button;
		boolean open;

		Legend() {
			for (int i = 0; i < ROWS.length; i++) {
				keys[i] = new Label(Type.BADGE, ROWS[i][0].toUpperCase(Locale.ROOT));
				actions[i] = new Label(Type.ROW_NOTE, ROWS[i][1]);
			}
			button = add(new Parts.Square(Glyph.INFO, "Shortcuts and movement", () -> {
				open = !open;
				invalidate();
			}).active(() -> open));
		}

		@Override
		protected void layout() {
			button.setBounds(x + r(40), y + h - r(40) - r(84), r(84), r(84));
		}

		@Override
		protected void paint(Canvas c) {
			if (!open) {
				return;
			}
			float rowH = r(52);
			float pw = r(860);
			float ph = r(90) + ROWS.length * rowH + r(30);
			float px = button.x();
			float py = button.y() - r(20) - ph;
			MenuStyle.block(c, px, py, pw, ph, MenuStyle.radius(), 0, 0);
			MenuStyle.text(c, title, px + r(34), py + r(50), MenuStyle.TAB_TEXT_SELECTED);
			float keyW = r(300);
			for (int i = 0; i < ROWS.length; i++) {
				float ry = py + r(90) + i * rowH + rowH / 2;
				float kw = Math.min(keyW - r(20), keys[i].width(c) + r(28));
				MenuStyle.box(c, px + r(34), ry - r(18), kw, r(36), r(8), MenuStyle.HINT_KEY);
				MenuStyle.centered(c, keys[i], px + r(34) + kw / 2, ry, MenuStyle.HINT_KEY_TEXT, kw);
				MenuStyle.fitted(c, actions[i], px + r(34) + keyW, ry, MenuStyle.SENTENCE_TEXT, pw - keyW - r(60));
			}
		}
	}

	// ---------- HUDS ----------

	/**
	 * The HUDs palette: every HUD mod Flint has, searchable. A mod that isn't on screen is added (and selected) with a click;
	 * one that is gets selected. Escape or Close closes it.
	 */
	private final class Huds extends Node {
		private final Label title = new Label(Type.TAB, "HUDS");
		private final Label hint = new Label(Type.ROW_NOTE, "Click to add · then drag it into place");
		private final Label none = new Label(Type.EMPTY_NOTE, "No matching HUDs");
		private final Parts.Square close;
		private final SearchField search;
		private final Column list = new Column();
		private final ScrollView scroll;
		private @Nullable FontFace searchFace;

		Huds() {
			close = add(new Parts.Square(Glyph.CLOSE, "Close", this::close));
			search = add(new SearchField("Search HUDs...", 40));
			search.name("Search HUDs");
			search.onChange(text -> refilter());
			Catalog catalog = Catalog.get();
			for (ModuleDescriptor module : ModuleRegistry.all()) {
				// Only the HUD mods this line runs (1.8.9 lists those it has a backend for).
				if (module.hud() && module.status().usable() && Catalog.runs(module)) {
					Catalog.Mod mod = catalog.modFor(module.id());
					String name = mod != null && mod.module() == module ? mod.name() : module.name();
					list.add(new Entry(module, name, mod != null ? mod.icon() : Icon.GAUGE));
				}
			}
			scroll = add(new ScrollView(list));
		}

		void open() {
			setVisible(true);
			invalidate();
			if (screen.focusVisible()) {
				screen.focusFromKeyboard(search);
			} else {
				screen.focus(search);
			}
		}

		void close() {
			setVisible(false);
			search.setText("");
			screen.focus(null);
		}

		private void refilter() {
			String q = search.text().toLowerCase(Locale.ROOT).trim();
			for (int i = 0; i < list.childCount(); i++) {
				Entry e = (Entry) list.child(i);
				e.setVisible(q.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(q));
			}
			list.invalidate();
		}

		/** The panel takes the clicks on it, so they don't reach the HUD behind. */
		@Override
		public boolean interactive() {
			return true;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			return button == Kit.LEFT_BUTTON;
		}

		@Override
		protected void layout() {
			float sq = r(72);
			close.setBounds(x + w - r(24) - sq, y + r(24), sq, sq);
			search.setBounds(x + r(24), y + r(24) + sq + r(20), w - 2 * r(24), r(64));
			FontFace face = Type.SEARCH.face();
			if (face != searchFace) {
				searchFace = face;
				search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
					r(15), r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
			}
			float listY = search.bottom() + r(70);
			scroll.flatBar(MenuStyle.SCROLL_THUMB, r(24), r(120));
			scroll.setBounds(x, listY, w, Math.max(0, y + h - r(20) - listY));
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.block(c, x, y, w, h, MenuStyle.radius(), 0, 0);
			MenuStyle.text(c, title, x + r(34), close.y() + close.height() / 2, MenuStyle.TAB_TEXT_SELECTED);
			MenuStyle.fitted(c, hint, x + r(34), search.bottom() + r(36), MenuStyle.LABEL, w - r(68));
			boolean any = false;
			for (int i = 0; i < list.childCount(); i++) {
				any |= list.child(i).visible();
			}
			if (!any) {
				MenuStyle.centered(c, none, x + w / 2, scroll.y() + r(80), MenuStyle.EMPTY_NOTE, w - r(40));
			}
		}

		/** Stacks the visible entries. */
		private static final class Column extends Node {
			private static final float ROW = 92;

			@Override
			public float prefHeight(float width) {
				float n = 0;
				for (int i = 0; i < childCount(); i++) {
					if (child(i).visible()) {
						n++;
					}
				}
				return n * r(ROW);
			}

			@Override
			protected void layout() {
				float at = y;
				for (int i = 0; i < childCount(); i++) {
					Node e = child(i);
					if (e.visible()) {
						e.setBounds(x + r(24), at, w - 2 * r(24), r(ROW) - r(10));
						at += r(ROW);
					}
				}
			}
		}

		/** One HUD mod: its icon and name, and whether it's on screen. */
		private final class Entry extends Pressable {
			private final ModuleDescriptor module;
			private final String name;
			private final Label label;
			private final Icon icon;
			private final Label onScreen = new Label(Type.BADGE, "ON SCREEN");
			private final Label add = new Label(Type.BADGE, "ADD");

			Entry(ModuleDescriptor module, String name, Icon icon) {
				super(null);
				this.module = module;
				this.name = name;
				this.label = new Label(Type.NAME, name);
				this.icon = icon;
				name(name);
				setAction(() -> {
					// Adding turns it on and selects it in the editor; one already on screen is selected.
					screen.editor().setEnabled(module.id(), true);
					close();
					screen.editor().focusStage();
				});
			}

			private boolean on() {
				return ControlState.layout().module(module).enabled();
			}

			@Override
			public String narration() {
				return name + (on() ? ", on screen, select it" : ", add it");
			}

			@Override
			protected void paint(Canvas c) {
				boolean shown = on();
				MenuStyle.block(c, x, y, w, h, MenuStyle.radius(), hover, 0);
				MenuStyle.icon(c, icon, x + r(44), y + h / 2, r(36), shown ? MenuStyle.ICON_ON : MenuStyle.ICON_OFF);
				Label tag = shown ? onScreen : add;
				float tw = tag.width(c) + r(28);
				MenuStyle.box(c, x + w - r(20) - tw, y + h / 2 - r(18), tw, r(36), r(18), shown ? MenuStyle.BADGE : MenuStyle.DISABLED);
				MenuStyle.centered(c, tag, x + w - r(20) - tw / 2, y + h / 2, shown ? MenuStyle.BADGE_TEXT : MenuStyle.DISABLED_TEXT, tw);
				MenuStyle.fitted(c, label, x + r(84), y + h / 2, shown ? MenuStyle.NAME_ON : MenuStyle.NAME_OFF, w - r(84) - tw - r(40));
				MenuStyle.focusRing(c, x, y, w, h, r(14), focus);
			}
		}
	}
}

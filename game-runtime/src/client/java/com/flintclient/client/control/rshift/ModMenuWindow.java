package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.kit.Node;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Right Shift window, laid out as Dawn's (design pixels at 1920 × 1080, scaled with the window): a title row over the
 * game (Flint's mark and the date and time; the player at the right), a row of tabs (the open one in the accent with its
 * name) with Close at the right, and the open tab's page in the 1304 × 724 window below. It hosts the colour and list pickers every page shares, and unwinds Escape one
 * layer at a time (an open picker, then a question a page is asking) before the screen closes. Every opening starts on the
 * Mod Menu tab's grid.
 */
final class ModMenuWindow extends Node implements Host {
	/** Dawn's window: its width, the title row, the gap to the tabs, the tabs, the gap to the window and the window's height. */
	private static final float WIN_W = 1304;
	private static final float TITLE = 64;
	private static final float ABOVE_TABS = 40;
	private static final float TABS = Parts.Tab.H;
	private static final float BELOW_TABS = 16;
	private static final float WIN_H = 724;
	/** The least space kept around it on a small window. */
	private static final float EDGE = 24;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter TIME_12 = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ROOT);

	/** Where the window is: the mods, one of the settings tabs, profiles, the theme or the waypoint list. */
	enum Place {
		MODS,
		PAGE,
		PROFILES,
		THEME,
		WAYPOINTS
	}

	private final ModMenuScreen screen;
	private final Catalog catalog = Catalog.get();
	private final List<Parts.Tab> tabs = new ArrayList<>();
	private final Parts.Square close;
	private final Parts.ProfilePill profile;
	private final ModsPage mods;
	private final ColorPicker picker;
	private final ListPicker lists;
	private final Label date = new Label(Type.PILL, "");
	private final Label time = new Label(Type.PILL, "");
	private Place place = Place.MODS;
	private int pageIndex;
	private @Nullable Node page;
	private int clockMinute = -1;
	/** When the open tab's page was shown, for its fade in. */
	private long shownAt;
	private boolean sunUp = true;
	private float titleY;
	private float winX;
	private float winY;
	private float winW;
	private float winH;

	ModMenuWindow(ModMenuScreen screen) {
		this.screen = screen;
		tabs.add(add(new Parts.Tab("Mod Menu", Glyph.GRID, () -> place == Place.MODS, () -> show(Place.MODS, 0))));
		for (int i = 0; i < catalog.pages().size(); i++) {
			int index = i;
			Catalog.Page p = catalog.pages().get(i);
			if (p.unavailable() != null) {
				// A tab Flint can't offer at all (it needs an outside service) isn't shown.
				continue;
			}
			tabs.add(add(new Parts.Tab(p.name(), pageIcon(p.key()), () -> place == Place.PAGE && pageIndex == index,
				() -> show(Place.PAGE, index))));
		}
		tabs.add(add(new Parts.Tab("Profiles", Glyph.LAYERS, () -> place == Place.PROFILES, () -> show(Place.PROFILES, 0))));
		tabs.add(add(new Parts.Tab("Waypoints", Glyph.PIN, () -> place == Place.WAYPOINTS, () -> show(Place.WAYPOINTS, 0))));
		tabs.add(add(new Parts.Tab("Theme", Glyph.PALETTE, () -> place == Place.THEME, () -> show(Place.THEME, 0))));
		close = add(new Parts.Square(Glyph.CLOSE, "Close", screen::closeRequested).closer());
		profile = add(new Parts.ProfilePill(() -> show(Place.PROFILES, 0)));
		mods = add(new ModsPage(this));
		// The pickers go last, so they draw over every page.
		picker = add(new ColorPicker());
		lists = add(new ListPicker());
		show(Place.MODS, 0);
	}

	// ---------- places ----------

	/** Opens {@code where} ({@code index}: which settings tab). */
	void show(Place where, int index) {
		closePickers();
		if (page != null) {
			remove(page);
			page = null;
		}
		place = where;
		shownAt = Clock.millis();
		pageIndex = Math.clamp(index, 0, Math.max(0, catalog.pages().size() - 1));
		mods.setVisible(where == Place.MODS);
		page = switch (where) {
			case MODS -> null;
			case PAGE -> {
				Catalog.Page p = catalog.pages().get(pageIndex);
				String about = p.unavailable() != null ? "Not available in Flint" : switch (p.key()) {
					case "graphics" -> "How the game looks, in step with Minecraft's own menu";
					case "performance" -> "Frame rate, distances and FPS limits";
					default -> "Flint's " + p.name().toLowerCase(java.util.Locale.ROOT) + " settings";
				};
				yield new SettingsList(this, p.name(), about, pageNote(p),
					p.sections(), null, null);
			}
			case PROFILES -> new ProfilesPage();
			case THEME -> new ThemePage(this);
			case WAYPOINTS -> new WaypointsPage(this);
		};
		if (page != null) {
			// Under the pickers, which stay last.
			add(page);
			remove(picker);
			remove(lists);
			add(picker);
			add(lists);
		}
		invalidate();
	}

	Place place() {
		return place;
	}

	/** A settings tab's note: how much of it Flint has. */
	private static @Nullable String pageNote(Catalog.Page p) {
		if (p.unavailable() != null) {
			return "Unavailable in Flint: " + p.unavailable() + ". These settings are listed for reference only.";
		}
		for (Catalog.Section s : p.sections()) {
			for (Catalog.Row r : s.rows()) {
				if (r.inFlint()) {
					return null;
				}
			}
		}
		return "Coming to Flint later.";
	}

	/** Opens the page of a mod (the Mod Menu tab, then the mod), e.g. from the HUD page or a settings shortcut. */
	void openMod(Catalog.Mod mod) {
		show(Place.MODS, 0);
		mods.open(mod);
	}

	@Override
	public void openModule(String moduleId) {
		Catalog.Mod mod = catalog.modFor(moduleId);
		if (mod != null) {
			openMod(mod);
		}
	}

	@Override
	public void openColorPicker(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set, ColorPicker.@Nullable Clear clear) {
		lists.close();
		picker.show(anchor, name, defaultRgb, get, set, clear);
	}

	@Override
	public void openChooser(Node anchor, String name, List<ListPicker.Item> items, boolean allowNone, Supplier<String> current, Consumer<String> choose,
		@Nullable Consumer<String> preview) {
		picker.close();
		lists.choose(anchor, name, items, allowNone, current, choose, preview);
	}

	@Override
	public void openValues(Node anchor, String name, List<ListPicker.Item> items, ListPicker.Values model, @Nullable Consumer<String> preview) {
		picker.close();
		lists.values(anchor, name, items, model, preview);
	}

	private void closePickers() {
		picker.close();
		lists.close();
	}

	/**
	 * Escape: an open picker, then a question the open page is asking (Reset Settings); false when the window should close.
	 * Text being typed into a field that cancels on Escape (a rename, a slider's typed value, the hex field) and a key box
	 * waiting for a key take Escape before this is asked.
	 */
	boolean escape() {
		if (picker.open()) {
			picker.close();
			return true;
		}
		if (lists.open()) {
			lists.close();
			return true;
		}
		if (place == Place.MODS) {
			return mods.escape();
		}
		if (page instanceof SettingsList list && list.escape()) {
			return true;
		}
		return false;
	}

	/** A settings tab's icon, by its page (a line that lists fewer pages keeps each one's icon). */
	private static Glyph pageIcon(String key) {
		return switch (key) {
			case "general" -> Glyph.GEAR;
			case "performance" -> Glyph.GAUGE;
			case "cosmetic" -> Glyph.GEM;
			case "social" -> Glyph.USERS;
			default -> Glyph.DISPLAY;
		};
	}

	// ---------- layout and drawing ----------

	@Override
	protected void layout() {
		Canvas c = measure();
		winW = Math.min(w - 2 * d(EDGE), d(WIN_W));
		winX = x + (w - winW) / 2;
		// Centred top to bottom while it fits; on a short window the window itself gives way.
		float above = d(TITLE) + d(ABOVE_TABS) + d(TABS) + d(BELOW_TABS);
		float top = y + Math.max(d(EDGE), (h - above - d(WIN_H)) / 2);
		titleY = top;
		float tabsY = top + d(TITLE) + d(ABOVE_TABS);
		winY = tabsY + d(TABS) + d(BELOW_TABS);
		winH = Math.max(d(300), Math.min(d(WIN_H), y + h - d(EDGE) - winY));
		float pw = profile.width(c);
		profile.setBounds(winX + winW - pw, titleY, pw, d(TITLE));
		// The tabs, 12 apart; on a narrow window the gaps close up (to 4) so they keep clear of Close, and when even that is
		// too wide Close moves up to the title row, left of the profile, so it always sits whole inside the window's edge.
		float tabsW = 0;
		for (Parts.Tab tab : tabs) {
			tabsW += tab.width(c);
		}
		float closeX = winX + winW - d(48);
		int gaps = Math.max(1, tabs.size() - 1);
		float gap = Math.clamp((closeX - d(12) - winX - tabsW) / gaps, d(4), d(12));
		float at = winX;
		for (Parts.Tab tab : tabs) {
			float tw = tab.width(c);
			tab.setBounds(at, tabsY, tw, d(TABS));
			at += tw + gap;
		}
		boolean closeOnTabs = at - gap + d(12) <= closeX;
		close.setBounds(closeOnTabs ? closeX : winX + winW - pw - d(12) - d(48), closeOnTabs ? tabsY + (d(TABS) - d(48)) / 2
			: titleY + (d(TITLE) - d(48)) / 2, d(48), d(48));
		mods.setBounds(winX, winY, winW, winH);
		if (page != null) {
			page.setBounds(winX, winY, winW, winH);
		}
		picker.setBounds(winX, winY, winW, winH);
		lists.setBounds(winX, winY, winW, winH);
	}

	/** Remakes the date and time once a minute, or when the 24-hour clock option changes (never per frame). */
	private void updateClock() {
		LocalDateTime now = LocalDateTime.now();
		boolean h24 = ControlState.settings().gameBool(GameOptions.CLOCK_24H);
		int minute = (now.getHour() * 60 + now.getMinute()) * 2 + (h24 ? 1 : 0);
		if (minute != clockMinute) {
			clockMinute = minute;
			date.set(DATE.format(now));
			time.set((h24 ? TIME : TIME_12).format(now));
			sunUp = now.getHour() >= 6 && now.getHour() < 19;
		}
	}

	/**
	 * The title row over the game (Flint's mark, 45 × 64, then 56 on the date and time block: 40 tall, its icon 16 and 22
	 * in, the date and the time 12 apart), then the window: a block.
	 */
	@Override
	protected void paint(Canvas c) {
		if (colorsVersion != MenuStyle.version()) {
			// A change of theme: controls that keep a look made from the colours (the search boxes) remake it.
			colorsVersion = MenuStyle.version();
			invalidate();
		}
		updateClock();
		float cy = titleY + d(TITLE) / 2;
		int markH = Math.max(1, c.px(d(TITLE)));
		c.mark(c.px(winX), c.px(cy) - markH / 2, markH, 1.0F);
		float blockX = winX + com.flintclient.client.render.MaskAtlas.markWidth(markH) / (float) c.scale() + d(56);
		float blockH = d(40);
		float blockW = d(22 + 16 + 12) + date.width(c) + d(12) + time.width(c) + d(22);
		MenuStyle.block(c, blockX, cy - blockH / 2, blockW, blockH, MenuStyle.radius(), 0, 0);
		(sunUp ? Glyph.SUN : Glyph.MOON).draw(c, blockX + d(22 + 8), cy, d(16), MenuStyle.TEXT);
		float textX = blockX + d(22 + 16 + 12);
		MenuStyle.text(c, date, textX, cy, MenuStyle.TEXT);
		MenuStyle.text(c, time, textX + date.width(c) + d(12), cy, MenuStyle.TEXT);
		if (screen.live()) {
			// Over a live game the window is frosted glass, and the game and the HUD stay sharp around it.
			MenuStyle.glass(c, winX, winY, winW, winH, MenuStyle.radius(), MenuStyle.seeThrough());
			if (screen.frosted()) {
				frost(c);
			}
			outlineCovered(c);
		} else {
			MenuStyle.block(c, winX, winY, winW, winH, MenuStyle.radius(), 0, 0);
		}
	}

	// ---------- over a live game ----------

	/** A HUD mod the window covers: a faint outline where it is. */
	private static final int COVERED = 0x47FFFFFF;
	/** The menu colours the layout was made with ({@link MenuStyle#version}). */
	private int colorsVersion = -1;
	/** Where a HUD mod's rectangle is read into (GUI units: x, y, w, h). */
	private final float[] hudRect = new float[4];

	/**
	 * Keeps the menu blur to the window (Frame#clipBlur): its rectangle as two overlapping ones inset at the corners, so the blur
	 * follows the rounding closely enough that no blurred corner shows outside it.
	 */
	private void frost(Canvas c) {
		int x0 = c.px(winX);
		int y0 = c.px(winY);
		int x1 = c.px(winX + winW);
		int y1 = c.px(winY + winH);
		int inset = Math.round(c.px(MenuStyle.radius()) * 0.3F);
		com.flintclient.client.platform.Frame.clipBlurTo(x0 + inset, y0, x1 - inset, y1);
		com.flintclient.client.platform.Frame.clipBlurTo(x0, y0 + inset, x1, y1 - inset);
		// Only the window: the top row's blocks are see-through over the sharp game (more rectangles left the clipped blur unreliable).
	}

	/** Each HUD mod on screen that the window covers, as a faint outline on the glass, so the player knows it is there. */
	private void outlineCovered(Canvas c) {
		int x0 = c.px(winX);
		int y0 = c.px(winY);
		int x1 = c.px(winX + winW);
		int y1 = c.px(winY + winH);
		int g = com.flintclient.client.platform.Frame.windowGuiScale();
		int line = Math.max(1, g / 2);
		// Only the part on the glass: outside it the mod itself shows.
		c.pushClip(x0, y0, x1, y1);
		try {
			for (int i = 0, n = com.flintclient.client.control.hud.render.HudRenderer.moduleCount(); i < n; i++) {
				if (!com.flintclient.client.control.hud.render.HudRenderer.lastRectAt(i, hudRect)) {
					continue;
				}
				int rx = Math.round(hudRect[0] * g);
				int ry = Math.round(hudRect[1] * g);
				int rw = Math.round(hudRect[2] * g);
				int rh = Math.round(hudRect[3] * g);
				if (rw > 0 && rh > 0 && rx < x1 && rx + rw > x0 && ry < y1 && ry + rh > y0) {
					c.ring(rx, ry, rw, rh, 2 * g, line, COVERED);
				}
			}
		} finally {
			c.popClip();
		}
	}

	/**
	 * The HUD mod the pointer is on (its card) or whose page is open, outlined in the accent where it sits on the screen, over
	 * the window too. Takes the frame's hover whatever happens, so it never outlives the frame.
	 */
	private void highlightHovered(Canvas c) {
		float amount = com.flintclient.client.control.hud.render.HudPreview.hoverAmount();
		String id = com.flintclient.client.control.hud.render.HudPreview.takeHovered();
		if (!screen.live()) {
			return;
		}
		if (id == null && place == Place.MODS) {
			Catalog.Mod open = mods.openMod();
			if (open != null && open.module() != null) {
				id = open.module().id();
				amount = 1;
			}
		}
		if (id == null) {
			return;
		}
		int index = com.flintclient.client.control.model.ModuleRegistry.indexOf(id);
		if (!com.flintclient.client.control.hud.render.HudRenderer.lastRectAt(index, hudRect)) {
			return;
		}
		int g = com.flintclient.client.platform.Frame.windowGuiScale();
		int pad = g;
		int rx = Math.round(hudRect[0] * g) - pad;
		int ry = Math.round(hudRect[1] * g) - pad;
		int rw = Math.round(hudRect[2] * g) + 2 * pad;
		int rh = Math.round(hudRect[3] * g) + 2 * pad;
		float k = Math.min(1, amount);
		c.frame().nextLayer();
		c.roundRect(rx, ry, rw, rh, 3 * g, com.flintclient.client.render.Palette.withAlpha(MenuStyle.ACCENT, 0.10F * k));
		c.ring(rx, ry, rw, rh, 3 * g, Math.max(1, g * 2 / 3), com.flintclient.client.render.Palette.withAlpha(MenuStyle.ACCENT, 0.95F * k));
	}

	/** The open tab's page fades and rises in as it is shown; the rest draws as it is. */
	@Override
	protected void paintChildren(Canvas c) {
		float k = MenuStyle.appear(shownAt, 200);
		for (int i = 0; i < childCount(); i++) {
			Node child = child(i);
			if (!child.visible()) {
				continue;
			}
			if (child == page || child == mods) {
				MenuStyle.appearing(c, child, k, d(10), Node::paintChild);
			} else {
				paintChild(c, child);
			}
		}
		highlightHovered(c);
	}

	/** Whether a point is on the window (a click outside it closes nothing, but the screen may use this). */
	boolean onWindow(float px, float py) {
		return px >= winX && px < winX + winW && py >= winY && py < winY + winH;
	}
}

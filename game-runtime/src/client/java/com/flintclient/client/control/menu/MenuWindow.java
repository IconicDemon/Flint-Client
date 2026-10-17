package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.editor.HudEditor;
import com.flintclient.client.control.menu.MenuControls.Chip;
import com.flintclient.client.control.menu.MenuControls.IconButton;
import com.flintclient.client.control.menu.MenuControls.NavTab;
import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.ScrollView;
import com.flintclient.client.ui.kit.SearchField;
import com.flintclient.client.ui.kit.TextField;
import java.time.LocalTime;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The module window. A header with the Flint mark and wordmark, the sections (MODS, SETTINGS, PROFILES, PRESETS,
 * WAYPOINTS), the time, and on the right Edit HUD (back to the HUD editor; in a world), THEMES (the brush) and Close.
 * Under it the content, full width: on MODS the filter bar (category chips with counts, the favourites filter, the
 * cards/compact view switch and the search, which also finds settings) over the scrolling grid, or a module's settings
 * page in its place; on the other sections their page. A page fades in when it changes. Under the window, the
 * "RIGHT SHIFT to close" hint. Sizes are in R ({@link MenuStyle#r}); the window keeps its share of the screen and
 * shrinks only when the screen is too small for it.
 *
 * <p>Escape unwinds one layer at a time: an open list or colour picker, then a question or rename on the page, then the
 * settings page (back to the grid) or a section (back to MODS; to the HUD editor when the editor's shortcut opened it),
 * then a focused search, and only then the menu itself.
 */
final class MenuWindow extends Node {
	private static final float WIN_W = 2272;
	private static final float WIN_H = 1520;
	private static final float HEADER = 142;
	private static final float RADIUS = 24;
	private static final float HINT_GAP = 35;
	private static final float HINT_H = 37;
	private static final float SIDE = 48;
	private static final float BUTTON = 72;
	private static final long CLOCK_EVERY_MILLIS = 1000;

	private final MenuState state;
	private final MenuScreen screen;
	private final NavTab[] tabs;
	private final IconButton close;
	private final IconButton themes;
	private final IconButton editHud;
	/** The other sections' content, made when first shown, in a fader. */
	private @Nullable Fader tabPanel;
	private MenuState.@Nullable Tab panelFor;
	private final Chip[] chips;
	private final IconButton favorites;
	private final IconButton view;
	private final SearchField search;
	private final ModGrid grid;
	private final ScrollView scroll;
	/** A module's settings page, in place of the filter bar and the grid while one is open. */
	private @Nullable ModulePage page;
	private @Nullable Fader pageFader;
	/** The colour picker and the list picker, over everything in the window while open. */
	private final ColorPicker picker;
	private final ListPicker lists;
	private final Label wordmark = new Label(Type.WORDMARK, "FLINT");
	private final Label clock = new Label(Type.TAB, "");
	private final Label hintKey = new Label(Type.HINT_KEY, "RIGHT SHIFT");
	private final Label hintText = new Label(Type.HINT, "to close");
	private final Label empty = new Label(Type.EMPTY, "");
	private final Label emptyNote = new Label(Type.EMPTY_NOTE, "Try another name, or another category.");
	private FontFace searchFace;
	private long clockAt = -CLOCK_EVERY_MILLIS;
	private int clockMinute = -1;
	private float winX;
	private float winY;
	private float winW;
	private float winH;
	private float contentX;
	private float contentY;
	private float contentW;
	private float contentH;

	MenuWindow(MenuScreen screen, MenuState state) {
		this.screen = screen;
		this.state = state;
		MenuState.Tab[] all = MenuState.Tab.values();
		tabs = new NavTab[all.length - 1];
		int k = 0;
		for (MenuState.Tab tab : all) {
			if (tab == MenuState.Tab.THEMES) {
				continue;
			}
			tabs[k++] = add(new NavTab(tab.name(), () -> state.tab == tab, () -> {
				if (tab == MenuState.Tab.MODS && state.tab == MenuState.Tab.MODS) {
					// MODS also leads back from a module's settings page.
					closePage();
				}
				selectTab(tab);
			}));
		}
		editHud = add(new IconButton(Icon.MOVE, 30, 12, MenuStyle.CLOSE, MenuStyle.CLOSE_HOVER, MenuStyle.CLOSE_EDGE, MenuStyle.CLOSE_MARK,
			screen::editHud));
		editHud.name("Edit HUD layout");
		editHud.tooltip("Back to the HUD editor");
		themes = add(new IconButton(Icon.BRUSH, 30, 12, MenuStyle.CLOSE, MenuStyle.CLOSE_HOVER, MenuStyle.CLOSE_EDGE, MenuStyle.CLOSE_MARK,
			() -> selectTab(state.tab == MenuState.Tab.THEMES ? MenuState.Tab.MODS : MenuState.Tab.THEMES)).active(() -> state.tab == MenuState.Tab.THEMES));
		themes.name("Themes");
		themes.tooltip("Themes: the menu's and the HUD's look");
		close = add(new IconButton(Icon.CLOSE, 32, 12, MenuStyle.CLOSE, MenuStyle.CLOSE_HOVER, MenuStyle.CLOSE_EDGE, MenuStyle.CLOSE_MARK,
			screen::onClose));
		close.name("Close");
		MenuCatalog.Category[] categories = MenuCatalog.Category.values();
		chips = new Chip[categories.length];
		for (int i = 0; i < categories.length; i++) {
			MenuCatalog.Category category = categories[i];
			chips[i] = add(new Chip(category, () -> state.category == category, () -> selectCategory(category)));
		}
		favorites = add(new IconButton(Icon.STAR, 28, 12, MenuStyle.VIEW, MenuStyle.VIEW_HOVER, 0, MenuStyle.VIEW_ICON, this::toggleFavoritesOnly)
			.active(() -> state.favoritesOnly));
		favorites.name("Favourites only");
		favorites.tooltip("Show only the mods you starred");
		grid = new ModGrid(state, this::openPage);
		view = add(new IconButton(Icon.LIST, 28, 12, MenuStyle.VIEW, MenuStyle.VIEW_HOVER, 0, MenuStyle.VIEW_ICON, this::toggleView)
			.active(grid::compact));
		view.name("Compact view");
		view.tooltip("Cards or compact rows");
		search = add(new SearchField("Search mods and settings", 50));
		search.name("Search mods and settings");
		search.setText(state.query);
		search.onChange(this::searchChanged);
		search.onEscape(() -> screen.focus(null));
		scroll = add(new ScrollView(grid));
		picker = add(new ColorPicker());
		lists = add(new ListPicker());
		if (state.page != null) {
			openPage(state.page);
		}
		if (state.tab != MenuState.Tab.MODS) {
			selectTab(state.tab);
		}
	}

	// ---------- sections ----------

	/** Shows a section: MODS (the grid or the open page), or another section's page (made the first time it shows). */
	void selectTab(MenuState.Tab tab) {
		boolean changed = state.tab != tab;
		state.tab = tab;
		picker.close();
		lists.close();
		Fader old = tabPanel;
		if (old != null && panelFor != tab) {
			remove(old);
			tabPanel = null;
			panelFor = null;
		}
		boolean mods = tab == MenuState.Tab.MODS;
		Fader shownPage = pageFader;
		if (shownPage != null) {
			shownPage.setVisible(mods);
			if (mods && changed) {
				shownPage.restart();
			}
		}
		gridShown(mods && page == null);
		if (!mods && tabPanel == null) {
			Node made = switch (tab) {
				case SETTINGS -> new ClientPage();
				case PROFILES -> new ProfilesPage();
				case PRESETS -> new PresetsPage();
				case THEMES -> new ThemesPage(this);
				default -> new WaypointsPage(this);
			};
			tabPanel = underPickers(new Fader(made));
			panelFor = tab;
		}
		KitScreen kit = screen();
		if (kit != null) {
			kit.focus(null);
		}
		invalidate();
	}

	/** Adds {@code node} under the pickers, which stay on top. */
	private <T extends Node> T underPickers(T node) {
		remove(picker);
		remove(lists);
		add(node);
		add(picker);
		add(lists);
		return node;
	}

	/** The PROFILES section (the HUD editor's Profiles shortcut). */
	void openProfiles() {
		selectTab(MenuState.Tab.PROFILES);
	}

	// ---------- a module's settings page ----------

	/** Opens {@code moduleId}'s settings page in place of the grid (the grid keeps its filter and scroll). */
	void openPage(String moduleId) {
		Fader old = pageFader;
		if (old != null) {
			remove(old);
		}
		picker.close();
		lists.close();
		ModulePage made = new ModulePage(this, state, moduleId);
		page = made;
		pageFader = underPickers(new Fader(made));
		state.page = moduleId;
		if (state.tab != MenuState.Tab.MODS) {
			selectTab(MenuState.Tab.MODS);
		}
		state.tab = MenuState.Tab.MODS;
		gridShown(false);
		invalidate();
	}

	/** Back to the grid from a settings page. */
	void closePage() {
		Fader old = pageFader;
		if (old == null) {
			return;
		}
		picker.close();
		lists.close();
		remove(old);
		page = null;
		pageFader = null;
		state.page = null;
		gridShown(true);
		KitScreen kit = screen();
		if (kit != null) {
			kit.focus(null);
		}
		invalidate();
	}

	private void gridShown(boolean shown) {
		for (Chip chip : chips) {
			chip.setVisible(shown);
		}
		favorites.setVisible(shown);
		view.setVisible(shown);
		search.setVisible(shown);
		scroll.setVisible(shown);
	}

	/** The HUD editor with {@code moduleId} selected; leaving it comes back to this menu, on the same page. */
	void openEditor(String moduleId) {
		screen.openEditor(moduleId);
	}

	/** The colour picker under {@code anchor}, for a colour called {@code name}. */
	void openColorPicker(Node anchor, String name, int defaultRgb, IntSupplier get, IntConsumer set) {
		picker.show(anchor, name, defaultRgb, get, set);
	}

	/** The list picker as a chooser (one sound, one item...). */
	void openChooser(Node anchor, String name, java.util.List<ListPicker.Item> items, boolean allowNone, java.util.function.Supplier<String> current,
		java.util.function.Consumer<String> choose, java.util.function.@Nullable Consumer<String> preview) {
		lists.choose(anchor, name, items, allowNone, current, choose, preview);
	}

	/** The list picker as a list of per-entry values (or ticks). */
	void openValues(Node anchor, String name, java.util.List<ListPicker.Item> items, ListPicker.Values model,
		java.util.function.@Nullable Consumer<String> preview) {
		lists.values(anchor, name, items, model, preview);
	}

	private void selectCategory(MenuCatalog.Category category) {
		if (state.category != category) {
			state.category = category;
			grid.refilter();
			scroll.model().scrollTo(0, true);
		}
	}

	private void toggleFavoritesOnly() {
		state.favoritesOnly = !state.favoritesOnly;
		grid.refilter();
		scroll.model().scrollTo(0, true);
	}

	private void toggleView() {
		grid.setCompact(!grid.compact());
		scroll.model().scrollTo(0, true);
	}

	private void searchChanged(String text) {
		state.query = text;
		grid.refilter();
		scroll.model().scrollTo(0, true);
	}

	/** Restores the grid's scroll position from the session (after the first layout). */
	void restoreScroll() {
		scroll.model().scrollTo(state.scroll, true);
	}

	/** Remembers the grid's scroll position for the next open. */
	void rememberScroll() {
		state.scroll = scroll.model().offset();
	}

	/** Escape: one layer at a time (see the class comment); false when it's the menu's to close. */
	boolean escape() {
		if (lists.open()) {
			lists.close();
			return true;
		}
		if (picker.open()) {
			picker.close();
			return true;
		}
		if (state.tab != MenuState.Tab.MODS) {
			Node panel = tabPanel != null ? tabPanel.content : null;
			if (panel instanceof WaypointsPage waypoints && waypoints.escape() || panel instanceof PresetsPage presets && presets.escape()
				|| panel instanceof ProfilesPage profiles && profiles.escape()) {
				return true;
			}
			selectTab(MenuState.Tab.MODS);
			// From the editor's shortcut, Escape goes back to the editor (the screen's call); otherwise to the mods.
			return !screen.cameFromShortcut();
		}
		ModulePage shownPage = page;
		if (shownPage != null) {
			if (!shownPage.escape()) {
				closePage();
			}
			return true;
		}
		KitScreen kit = screen();
		if (kit != null && kit.focused() == search) {
			kit.focus(null);
			return true;
		}
		return false;
	}

	// ---------- layout ----------

	@Override
	protected void layout() {
		Canvas c = measure();
		float margin = Math.max(r(24), 6);
		winW = Math.min(r(WIN_W), w - 2 * margin);
		float hintSpace = r(HINT_GAP + HINT_H) + margin;
		winH = Math.min(r(WIN_H), h - 2 * hintSpace);
		winX = x + (float) Math.floor((w - winW) / 2);
		winY = y + (float) Math.floor((h - winH) / 2);

		// Header: the sections after the wordmark; Edit HUD, Themes and Close at the right.
		float headY = winY + (r(HEADER) - r(BUTTON)) / 2;
		close.setBounds(winX + winW - r(40) - r(BUTTON), headY, r(BUTTON), r(BUTTON));
		themes.setBounds(close.x() - r(16) - r(BUTTON), headY, r(BUTTON), r(BUTTON));
		editHud.setBounds(themes.x() - r(16) - r(BUTTON), headY, r(BUTTON), r(BUTTON));
		editHud.setVisible(ControlState.inWorld() && HudEditor.available());
		int markH = c.px(r(68));
		float markW = (float) MaskAtlas.markWidth(markH) / c.scale();
		float at = winX + r(54) + markW + r(29) + wordmark.width(c) + r(70);
		float tabY = winY + (r(HEADER) - r(NavTab.H)) / 2;
		for (NavTab tab : tabs) {
			float tw = tab.width(c);
			tab.setBounds(at, tabY, tw, r(NavTab.H));
			at += tw + r(13);
		}

		contentX = winX + r(SIDE);
		contentW = winW - 2 * r(SIDE);
		contentY = winY + r(HEADER) + r(40);
		contentH = Math.max(0, winY + winH - r(34) - contentY);

		// Filter bar: the chips from the left, then favourites and the view switch; the search fills to the right edge.
		float bar = r(Chip.H);
		at = contentX;
		for (Chip chip : chips) {
			float cw = chip.width(c);
			chip.setBounds(at, contentY, cw, bar);
			at += cw + r(17);
		}
		at += r(1);
		favorites.setBounds(at, contentY, bar, bar);
		at += bar + r(12);
		view.setBounds(at, contentY, bar, bar);
		float right = contentX + contentW;
		float searchX = Math.max(at + bar + r(30), right - r(560));
		search.setBounds(searchX, contentY, Math.max(r(200), right - searchX), bar);
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
				r(15), r(24), r(30), r(18), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}

		if (pageFader != null) {
			pageFader.setBounds(contentX, contentY, contentW, contentH);
		}
		if (tabPanel != null) {
			tabPanel.setBounds(contentX, contentY, contentW, contentH);
		}
		picker.setBounds(winX, winY, winW, winH);
		lists.setBounds(winX, winY, winW, winH);

		// Grid: below the filter bar to the window's bottom edge, with a gutter on the right for the scroll bar.
		float gridY = contentY + bar + r(36);
		scroll.flatBar(MenuStyle.SCROLL_THUMB, r(30), r(150));
		scroll.setBounds(contentX, gridY, contentW, Math.max(0, winY + winH - MenuStyle.edge() / (float) screenScale() - gridY));
	}

	private int screenScale() {
		KitScreen kit = screen();
		return kit != null ? kit.uiScale() : 1;
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		int x0 = c.px(winX);
		int y0 = c.px(winY);
		int pw = c.px(winX + winW) - x0;
		int ph = c.px(winY + winH) - y0;
		int rad = c.px(MenuStyle.round(r(RADIUS)));
		int line = MenuStyle.edge();

		// A soft, low shadow for depth.
		for (int i = 3; i >= 1; i--) {
			int spread = c.px(r(10 * i));
			c.roundRect(x0 - spread, y0 - spread + c.px(r(14)), pw + 2 * spread, ph + 2 * spread, rad + spread, Palette.withAlpha(0xFF000000, 0.07F));
		}
		c.roundRect(x0, y0, pw, ph, rad, MenuStyle.WINDOW);

		// Header band, then the content's gentle vertical gradient.
		int headerB = c.px(winY + r(HEADER));
		c.roundRectCorners(x0, y0, pw, headerB - y0, rad, MenuStyle.HEADER, Canvas.TOP_LEFT | Canvas.TOP_RIGHT);
		int gradientB = Math.min(c.px(winY + r(HEADER + 520)), y0 + ph - rad);
		c.gradient(x0, headerB, x0 + pw, gradientB, MenuStyle.CONTENT_TOP, MenuStyle.CONTENT_LOW);
		c.roundRectCorners(x0, gradientB, pw, y0 + ph - gradientB, rad, MenuStyle.CONTENT_LOW, Canvas.BOTTOM_LEFT | Canvas.BOTTOM_RIGHT);
		c.fill(x0, headerB, x0 + pw, headerB + line, MenuStyle.HEADER_RULE);

		// The Flint mark and wordmark.
		int markH = c.px(r(68));
		int markX = c.px(winX + r(54));
		c.mark(markX, c.px(winY + r(HEADER / 2)) - markH / 2, markH, 1);
		float wordX = (float) (markX + MaskAtlas.markWidth(markH)) / c.scale() + r(29);
		MenuStyle.text(c, wordmark, wordX, winY + r(HEADER / 2), MenuStyle.WORDMARK);

		// The time, before the header's buttons (made again only when the minute changes).
		updateClock();
		Node first = editHud.visible() ? editHud : themes;
		float clockX = first.x() - r(28) - clock.width(c);
		NavTab lastTab = tabs[tabs.length - 1];
		if (clockX > lastTab.x() + lastTab.width() + r(30)) {
			MenuStyle.text(c, clock, clockX, winY + r(HEADER / 2), MenuStyle.TAB_TEXT);
		}

		paintHint(c);
		if (page == null && state.tab == MenuState.Tab.MODS && grid.shown() == 0) {
			paintEmpty(c);
		}
	}

	private void updateClock() {
		long now = Util.getMillis();
		if (now - clockAt < CLOCK_EVERY_MILLIS) {
			return;
		}
		clockAt = now;
		LocalTime time = LocalTime.now();
		int minute = time.getHour() * 60 + time.getMinute();
		if (minute != clockMinute) {
			clockMinute = minute;
			clock.set(String.format(java.util.Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()));
		}
	}

	/** "RIGHT SHIFT to close" under the window: the key in a small grey key cap, then the words. */
	private void paintHint(Canvas c) {
		hintKey.set(MenuScreen.openKeyNameUpper());
		float keyW = hintKey.width(c) + 2 * r(16);
		float gap = r(14);
		float total = keyW + gap + hintText.width(c);
		float hx = winX + (winW - total) / 2;
		float hy = winY + winH + r(HINT_GAP);
		float cy = hy + r(HINT_H) / 2;
		MenuStyle.box(c, hx, hy, keyW, r(HINT_H), r(8), MenuStyle.HINT_KEY);
		MenuStyle.text(c, hintKey, hx + r(16), cy, MenuStyle.HINT_KEY_TEXT);
		MenuStyle.text(c, hintText, hx + keyW + gap, cy, MenuStyle.HINT_TEXT);
	}

	/** No card matches: a short message in the middle of the grid's space. */
	private void paintEmpty(Canvas c) {
		String q = state.query.strip();
		if (state.favoritesOnly && q.isEmpty()) {
			empty.set("No favourites here yet");
			emptyNote.set("Star a mod to keep it here, or turn the star filter off.");
		} else {
			empty.set(q.isEmpty() ? "No mods in this category" : "Nothing matches “" + q + "”");
			emptyNote.set(state.favoritesOnly ? "The star filter is on: only starred mods show." : "Try another word, or another category.");
		}
		float cx = scroll.x() + scroll.width() / 2;
		float cy = scroll.y() + Math.min(scroll.height(), r(700)) / 2;
		MenuStyle.centered(c, empty, cx, cy - r(22), MenuStyle.EMPTY_TEXT, scroll.width() - r(80));
		MenuStyle.centered(c, emptyNote, cx, cy + r(26), MenuStyle.EMPTY_NOTE, scroll.width() - r(80));
	}

	@Override
	protected void paintChildren(Canvas c) {
		super.paintChildren(c);
		// Fades where the grid continues past its viewport, then the window's edge.
		int gx0 = c.px(scroll.x());
		int gx1 = c.px(scroll.x() + scroll.width());
		int gy0 = c.px(scroll.y());
		int gy1 = c.px(scroll.y() + scroll.height());
		float offset = scroll.model().offset();
		float max = scroll.model().maxOffset();
		boolean gridOn = page == null && state.tab == MenuState.Tab.MODS;
		if (gridOn && max - offset > 0.5F) {
			float t = Math.min(1, (max - offset) / r(60));
			int fade = c.px(r(120));
			c.gradient(gx0, gy1 - fade, gx1, gy1, MenuStyle.CONTENT_LOW & 0xFFFFFF, Palette.withAlpha(MenuStyle.CONTENT_LOW, 0.92F * t));
		}
		if (gridOn && offset > 0.5F) {
			float t = Math.min(1, offset / r(60));
			int fade = c.px(r(46));
			c.gradient(gx0, gy0, gx1, gy0 + fade, Palette.withAlpha(MenuStyle.CONTENT_TOP, 0.9F * t), MenuStyle.CONTENT_TOP & 0xFFFFFF);
		}
		int x0 = c.px(winX);
		int y0 = c.px(winY);
		c.ring(x0, y0, c.px(winX + winW) - x0, c.px(winY + winH) - y0, c.px(MenuStyle.round(r(RADIUS))), MenuStyle.edge(), MenuStyle.WINDOW_EDGE);
	}

	/** Whether ({@code px}, {@code py}) is on the window. */
	boolean onWindow(float px, float py) {
		return px >= winX && px < winX + winW && py >= winY && py < winY + winH;
	}

	/**
	 * A page's holder: the page fills it, and it fades in over about 150 ms with a small rise when it first shows (or
	 * {@link #restart} is called). Nothing moves once it has settled.
	 */
	static final class Fader extends Node {
		private static final float TAU = 0.05F;
		final Node content;
		private float shown;

		Fader(Node content) {
			this.content = add(content);
		}

		void restart() {
			shown = 0;
		}

		@Override
		protected void layout() {
			content.setBounds(x, y, w, h);
		}

		@Override
		protected void paintChildren(Canvas c) {
			if (shown < 1) {
				shown = Kit.reduced() ? 1 : Kit.approach(shown, 1, TAU);
				if (shown > 0.995F) {
					shown = 1;
				}
			}
			if (shown >= 1) {
				super.paintChildren(c);
				return;
			}
			float previous = c.opacity();
			c.setOpacity(previous * shown);
			try {
				super.paintChildren(c);
			} finally {
				c.setOpacity(previous);
			}
		}
	}
}

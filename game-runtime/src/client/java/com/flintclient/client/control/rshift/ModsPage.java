package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
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
 * The Mod Menu tab, as Dawn's mods page (design pixels): the filter row (All and the categories, then Coming Soon; search;
 * favourites only; compact view) and the mods as 305 × 210 cards in four columns, 12 apart (or 622 × 64 rows in two). Only mods that work in Flint are listed, unless Coming Soon is chosen. A card's status button turns its Flint
 * module on or off; the card itself and its gear open its settings page in place of the grid, and its star makes it a
 * favourite. The filters, the search and the scroll start afresh each time the menu opens (the whole grid, at the top);
 * favourites and the compact view are saved.
 */
final class ModsPage extends Node {
	/** The page's padding, the filter row's height (24 above its 40-tall buttons, 12 below) and the gaps. */
	private static final float PAD = 24;
	private static final float ROW = 76;
	private static final float GAP = 12;
	/** A compact row's height. */
	private static final float ROW_COMPACT = 64;

	/** The filters, for this opening of the menu only (the next one starts afresh). */
	private Catalog.@Nullable Category category;
	/** The Coming Soon chip: mods Flint doesn't have (or can't offer) yet, instead of the ones it does. */
	private boolean comingSoon;
	private boolean favoritesOnly;
	private String query = "";
	/** Where the grid was scrolled to when a mod's page opened, for going back to it. */
	private float scrolled;

	private final Host host;
	private final Catalog catalog;
	private final List<Parts.Chip> chips = new ArrayList<>();
	private final SearchField search;
	private final Parts.Square favorites;
	private final Parts.Square compact;
	private final Grid grid;
	private final ScrollView scroll;
	private final Label empty = new Label(Type.EMPTY, "");
	private final Label emptyNote = new Label(Type.EMPTY_NOTE, "");
	private final String summary;
	private @Nullable FontFace searchFace;
	private @Nullable SettingsList page;
	private Catalog.@Nullable Mod pageMod;
	private int shown;
	/** When the grid was last shown or filtered, and when a mod's page opened: their fade ins. */
	private long gridAt;
	private long pageAt;
	/** A card being dragged (to reorder the favourites), where the pointer is, where on the card it took hold, and the card under it. */
	private @Nullable Card dragged;
	private float dragX;
	private float dragY;
	private float grabX;
	private float grabY;
	private @Nullable Card dropTarget;

	ModsPage(Host host) {
		this.host = host;
		this.catalog = Catalog.get();
		int working = 0;
		for (Catalog.Mod m : catalog.mods()) {
			if (works(m)) {
				working++;
			}
		}
		summary = working + " mods · hover a mod to read about it";
		chips.add(add(new Parts.Chip("All", working, () -> category == null && !comingSoon, () -> select(null, false))));
		for (Catalog.Category c : Catalog.Category.values()) {
			int n = count(c);
			// The reference's "New" chip lists new mods; Flint marks none, so an empty chip isn't shown.
			if (n > 0) {
				chips.add(add(new Parts.Chip(c.label(), n, () -> category == c && !comingSoon, () -> select(c, false))));
			}
		}
		int later = catalog.mods().size() - working;
		if (later > 0) {
			chips.add(add(new Parts.Chip("Coming Soon", later, () -> comingSoon, () -> select(null, true))));
		}
		grid = new Grid();
		for (Catalog.Mod m : catalog.mods()) {
			grid.add(new Card(m));
		}
		search = add(new SearchField("Search", 50));
		search.name("Search mods and settings");
		search.onChange(text -> {
			query = text;
			refilter();
		});
		favorites = add(new Parts.Square(Glyph.HEART, "Favourites only", () -> {
			favoritesOnly = !favoritesOnly;
			refilter();
		}).active(() -> favoritesOnly));
		compact = add(new Parts.Square(Glyph.BORDER_ALL, "Compact view", () -> {
			ControlState.settings().setCompactView(!ControlState.settings().compactView());
			grid.invalidate();
		}).active(() -> ControlState.settings().compactView()));
		scroll = add(new ScrollView(grid));
		refilter();
		gridAt = Clock.millis();
	}

	/** Whether a mod works in Flint: it runs on a Flint module that can be switched on. */
	static boolean works(Catalog.Mod mod) {
		return mod.module() != null && mod.module().status().usable() && mod.unavailable() == null;
	}

	private int count(Catalog.Category c) {
		int n = 0;
		for (Catalog.Mod m : catalog.mods()) {
			if (m.in(c) && works(m)) {
				n++;
			}
		}
		return n;
	}

	private void select(Catalog.@Nullable Category value, boolean later) {
		if (category != value || comingSoon != later) {
			category = value;
			comingSoon = later;
			gridAt = Clock.millis();
			refilter();
			scroll.model().scrollTo(0, true);
		}
	}

	/** Shows the cards that pass the chip, the favourites filter and every word of the search. */
	private void refilter() {
		String[] words = query.isBlank() ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		ControlSettings settings = ControlState.settings();
		shown = 0;
		for (int i = 0; i < grid.childCount(); i++) {
			Card card = (Card) grid.child(i);
			boolean pass = works(card.mod) != comingSoon && card.mod.in(category) && (!favoritesOnly || settings.isFavorite(card.mod.favoriteKey()))
				&& card.matches(words);
			card.setVisible(pass);
			if (pass) {
				shown++;
			}
		}
		if (favoritesOnly && shown == 0 && words.length == 0) {
			empty.set("No favourite mods here");
			emptyNote.set("Star a mod to add it, or turn off the favourites filter to see them all.");
		} else {
			empty.set("No matches");
			emptyNote.set("Try another name or setting, or another category.");
		}
		grid.invalidate();
	}

	/** A mod's page fades and rises in as it opens. */
	@Override
	protected void paintChildren(Canvas c) {
		float k = MenuStyle.appear(pageAt, 180);
		for (int i = 0; i < childCount(); i++) {
			Node child = child(i);
			if (!child.visible()) {
				continue;
			}
			if (child == page) {
				MenuStyle.appearing(c, child, k, d(8), Node::paintChild);
			} else {
				paintChild(c, child);
			}
		}
	}

	/** The visible card under ({@code mx}, {@code my}) other than {@code except}, or null. */
	private @Nullable Card cardAt(float mx, float my, Card except) {
		for (int i = 0; i < grid.childCount(); i++) {
			if (grid.child(i) instanceof Card card && card != except && card.visible() && card.containsPoint(mx, my)) {
				return card;
			}
		}
		return null;
	}

	/** Opens {@code mod}'s settings page in place of the grid. */
	void open(Catalog.Mod mod) {
		closePage();
		pageMod = mod;
		scrolled = scroll.model().target();
		// Pack Organizer lists the packs themselves under its settings; Tier Tagger opens the TierTagger mod's own screens.
		Node extra = mod.module() == null ? null : MenuHost.extraPanel(mod.module().id());
		page = add(new SettingsList(host, mod.name(), mod.description(), note(mod), mod.sections(), this::closePage, extra, !works(mod), mod));
		pageAt = Clock.millis();
		setGridShown(false);
		invalidate();
	}

	/** The mod whose page is open, or null. */
	Catalog.@Nullable Mod openMod() {
		return page != null ? pageMod : null;
	}

	private void closePage() {
		if (page == null) {
			return;
		}
		remove(page);
		page = null;
		pageMod = null;
		setGridShown(true);
		gridAt = Clock.millis();
		invalidate();
		scroll.model().scrollTo(scrolled, true);
	}

	private void setGridShown(boolean on) {
		for (Parts.Chip chip : chips) {
			chip.setVisible(on);
		}
		search.setVisible(on);
		favorites.setVisible(on);
		compact.setVisible(on);
		scroll.setVisible(on);
	}

	/** What a mod's page says above its settings: nothing for a mod that works; for one that is coming, that it is. */
	static @Nullable String note(Catalog.Mod mod) {
		if (works(mod)) {
			return null;
		}
		if (mod.unavailable() != null) {
			return "Unavailable in Flint: " + mod.unavailable() + ". These are the settings it would have.";
		}
		if (mod.module() != null && mod.module().statusNote() != null) {
			return mod.module().statusNote();
		}
		return "Coming to Flint. These are the settings it will have; they don't do anything yet.";
	}

	/**
	 * Escape: a question the open mod's page is asking; otherwise false, and the menu closes. Neither the mod's page nor the
	 * search holds it back (the back arrow and the search's own clear do that), and the next opening starts afresh anyway.
	 */
	boolean escape() {
		return page != null && page.escape();
	}

	@Override
	protected void layout() {
		float left = x + d(PAD);
		float right = x + w - d(PAD);
		float top = y + d(PAD);
		float bh = d(Parts.Chip.H);
		Canvas c = measure();
		float at = left;
		for (Parts.Chip chip : chips) {
			float cw = chip.width(c);
			chip.setBounds(at, top, cw, bh);
			at += cw + d(GAP);
		}
		compact.setBounds(right - bh, top, bh, bh);
		favorites.setBounds(compact.x() - d(GAP) - bh, top, bh, bh);
		float searchRight = favorites.x() - d(GAP);
		float searchX = Math.max(at + d(GAP), searchRight - d(310));
		search.setBounds(searchX, top, Math.max(d(120), searchRight - searchX), bh);
		FontFace face = Type.SEARCH.face();
		if (face != searchFace) {
			searchFace = face;
			search.look(new TextField.Look(face, MenuStyle.SEARCH, MenuStyle.SEARCH_EDGE, MenuStyle.SEARCH_EDGE_HOVER, MenuStyle.SEARCH_FOCUS,
				MenuStyle.radius(), d(16), d(16), d(16), MenuStyle.SEARCH_TEXT, MenuStyle.SEARCH_HINT, MenuStyle.SEARCH_HINT));
		}
		float gridY = y + d(ROW);
		scroll.flatBar(MenuStyle.SCROLL_THUMB, d(6), d(80));
		scroll.setBounds(x, gridY, w, Math.max(0, y + h - gridY));
		if (page != null) {
			page.setBounds(x, y, w, h);
		}
	}

	@Override
	protected void paint(Canvas c) {
		if (page != null) {
			return;
		}
		if (shown == 0) {
			float cy = scroll.y() + Math.min(d(140), scroll.height() / 2);
			MenuStyle.centered(c, empty, x + w / 2, cy, MenuStyle.EMPTY_TEXT, w - 2 * d(PAD));
			MenuStyle.centered(c, emptyNote, x + w / 2, cy + d(30), MenuStyle.EMPTY_NOTE, w - 2 * d(PAD));
		}
	}

	// ---------- the grid ----------

	/**
	 * Cards in four columns (compact rows in two), left to right, top to bottom, the favourites first in their own order; the
	 * cards keep Dawn's 305 × 210 shape at any width, 12 apart inside a padding of 10 above and 24 elsewhere. A dragged card
	 * is drawn last, over the others, where the pointer holds it.
	 */
	private final class Grid extends Node {
		private static final float ASPECT = 210F / 305F;
		private static final float ROW_H = ROW_COMPACT;
		private static final float TOP = 10;

		private boolean compact() {
			return ControlState.settings().compactView();
		}

		private int columns(float width) {
			return compact() ? 2 : 4;
		}

		private float cellH(float width) {
			if (compact()) {
				return d(ROW_H);
			}
			int cols = columns(width);
			float cellW = (width - 2 * d(PAD) - (cols - 1) * d(GAP)) / cols;
			return cellW * ASPECT;
		}

		private int visibleCount() {
			int n = 0;
			for (int i = 0; i < childCount(); i++) {
				if (child(i).visible()) {
					n++;
				}
			}
			return n;
		}

		/** The cards as shown: the favourites first, in the order the player gave them, then the rest in the catalog's order. */
		private List<Node> ordered() {
			List<Node> out = new ArrayList<>(childCount());
			for (String key : ControlState.settings().favorites()) {
				for (int i = 0; i < childCount(); i++) {
					if (child(i) instanceof Card card && card.mod.favoriteKey().equals(key)) {
						out.add(card);
						break;
					}
				}
			}
			for (int i = 0; i < childCount(); i++) {
				if (!out.contains(child(i))) {
					out.add(child(i));
				}
			}
			return out;
		}

		@Override
		protected void paintChildren(Canvas c) {
			Card held = dragged;
			for (int i = 0; i < childCount(); i++) {
				Node child = child(i);
				if (child != held && child.visible()) {
					// The cards come in one after another, each a few milliseconds after the one before (at most a quarter second).
					int order = child instanceof Card card ? card.order : 0;
					MenuStyle.appearing(c, child, MenuStyle.appear(gridAt + Math.min(order, 16) * 12L, 200), d(8), Node::paintChild);
				}
			}
			if (held == null) {
				return;
			}
			Card target = dropTarget;
			if (target != null) {
				MenuStyle.ring(c, target.x() - d(3), target.y() - d(3), target.width() + d(6), target.height() + d(6), MenuStyle.radius() + d(3),
					Math.max(2, c.px(d(2))), MenuStyle.ACCENT);
			}
			// The card follows the pointer, a little see-through, over everything else.
			int dx = Math.round((dragX - grabX - held.x()) * c.scale());
			int dy = Math.round((dragY - grabY - held.y()) * c.scale());
			float opacity = c.opacity();
			c.pushOffset(dx, dy);
			c.setOpacity(opacity * 0.9F);
			try {
				paintChild(c, held);
			} finally {
				c.setOpacity(opacity);
				c.popOffset();
			}
		}

		@Override
		public float prefHeight(float width) {
			int cols = columns(width);
			int rows = (visibleCount() + cols - 1) / cols;
			float cellH = cellH(width);
			return rows == 0 ? 0 : d(TOP) + rows * cellH + (rows - 1) * d(GAP) + d(PAD);
		}

		@Override
		protected void layout() {
			int cols = columns(w);
			float inner = w - 2 * d(PAD);
			float cellW = (inner - (cols - 1) * d(GAP)) / cols;
			float cellH = cellH(w);
			int n = 0;
			for (Node card : ordered()) {
				if (!card.visible()) {
					continue;
				}
				int col = n % cols;
				int row = n / cols;
				card.setBounds(x + d(PAD) + col * (cellW + d(GAP)), y + d(TOP) + row * (cellH + d(GAP)), cellW, cellH);
				((Card) card).order = n;
				n++;
			}
		}
	}

	/** One mod: icon, name, status button, gear and star. Pressing the card opens its page. */
	private final class Card extends Pressable {
		private final Catalog.Mod mod;
		private final Label name;
		private final Status status;
		private final Parts.Square gear;
		private final Star star;
		/** Lower-case name, description and every row label, for search. */
		private final String haystack;
		/** Its place among the shown cards (for the staggered fade in). */
		int order;
		/** How on the card looks, eased (its name and icon brighten as it turns on). */
		private float lit = -1;

		Card(Catalog.Mod mod) {
			super(null);
			this.mod = mod;
			this.name = new Label(Type.NAME, mod.name());
			name(mod.name());
			setAction(() -> open(mod));
			status = add(new Status(mod));
			gear = add(new Parts.Square(Glyph.GEAR, mod.name() + " settings", () -> open(mod)));
			star = add(new Star(mod));
			StringBuilder words = new StringBuilder(mod.name()).append(' ').append(mod.description());
			for (Catalog.Section s : mod.sections()) {
				words.append(' ').append(s.title());
				for (Catalog.Row row : s.rows()) {
					words.append(' ').append(row.label());
				}
			}
			haystack = words.toString().toLowerCase(Locale.ROOT);
		}

		float hoverAmount() {
			return Math.max(hover, Math.max(status.hoverAmount(), star.hoverAmount()));
		}

		private float downX;
		private float downY;
		private boolean dragging;

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			downX = mx;
			downY = my;
			dragging = false;
			return super.mouseDown(mx, my, button);
		}

		/** Held and moved past a few pixels: the card is dragged (to a favourite's place) instead of pressed. */
		@Override
		public void mouseDrag(float mx, float my) {
			if (!dragging && Math.hypot(mx - downX, my - downY) > d(5)) {
				dragging = true;
				dragged = this;
				grabX = downX - x;
				grabY = downY - y;
			}
			if (dragging) {
				dragX = mx;
				dragY = my;
				dropTarget = cardAt(mx, my, this);
			}
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			if (!dragging) {
				super.mouseUp(mx, my, button);
				return;
			}
			dragging = false;
			Card target = cardAt(mx, my, this);
			dragged = null;
			dropTarget = null;
			ControlSettings settings = ControlState.settings();
			if (target != null && settings.isFavorite(target.mod.favoriteKey())) {
				// Dropped on a favourite: this card takes that place among the favourites (it becomes one if it wasn't).
				settings.moveFavorite(mod.favoriteKey(), settings.favorites().indexOf(target.mod.favoriteKey()));
				grid.invalidate();
			}
		}

		boolean matches(String[] words) {
			for (String word : words) {
				if (!haystack.contains(word)) {
					return false;
				}
			}
			return true;
		}

		@Override
		public String narration() {
			return mod.name() + ", " + status.word() + ". Opens its settings";
		}

		/** Dawn's card, scaled from 305 wide: the heart 32 at 24 from the top right, the gear 40 and the 205-wide status at 24 from the bottom corners. */
		@Override
		protected void layout() {
			if (ControlState.settings().compactView()) {
				float k = h / d(ROW_COMPACT);
				float right = x + w - d(16) * k;
				float bh = d(40) * k;
				status.setBounds(right - d(205) * k, y + (h - bh) / 2, d(205) * k, bh);
				gear.setBounds(status.x() - d(GAP) * k - bh, y + (h - bh) / 2, bh, bh);
				float hs = d(32) * k;
				star.setBounds(gear.x() - d(GAP) * k - hs, y + (h - hs) / 2, hs, hs);
				return;
			}
			float k = w / d(305);
			float pad = d(24) * k;
			float bh = d(40) * k;
			gear.setBounds(x + pad, y + h - pad - bh, bh, bh);
			float sw = Math.min(d(205) * k, x + w - pad - gear.right() - d(GAP) * k);
			status.setBounds(x + w - pad - sw, y + h - pad - bh, sw, bh);
			float hs = d(32) * k;
			star.setBounds(x + w - pad - hs, y + pad, hs, hs);
		}

		/** Its name (20, 24 from the top left), and its picture: 56 tall from 67 down, at most 70% of the width across. */
		@Override
		protected void paint(Canvas c) {
			lit = MenuStyle.ease(lit, status.on());
			float rad = MenuStyle.radius();
			MenuStyle.block(c, x, y, w, h, rad, Math.max(hover, press), 0);
			MenuStyle.pressed(c, x, y, w, h, rad, press);
			int iconInk = Palette.lerp(MenuStyle.ICON_OFF, MenuStyle.ICON_ON, lit);
			int nameInk = Palette.lerp(MenuStyle.NAME_OFF, MenuStyle.NAME_ON, lit);
			if (ControlState.settings().compactView()) {
				float k = h / d(ROW_COMPACT);
				float icon = d(32) * k;
				drawModIcon(c, mod, x + d(16) * k + icon / 2, y + h / 2, icon, icon, iconInk);
				float nameX = x + d(16) * k + icon + d(16) * k;
				MenuStyle.fitted(c, name, nameX, y + h / 2, nameInk, star.x() - d(GAP) * k - nameX);
			} else {
				float k = w / d(305);
				float pad = d(24) * k;
				MenuStyle.fitted(c, name, x + pad, y + pad + d(12) * k, nameInk, Math.min(d(200) * k, star.x() - d(8) * k - x - pad));
				drawCardPicture(c, mod, x + w / 2, y + d(67 + 28) * k, w, k, iconInk, lit >= 0.5F);
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}

	}

	/** The Nametags card's picture: the owner's Flint tag, lit while the mod is on (full colour, pixel art). */
	private static final String NAMETAGS = "nametags";
	/** The Playtime card's picture: a time, in Minecraft's font like every other word in the menu. */
	private static final String PLAYTIME = "playtime";
	private static final String PLAYTIME_SAMPLE = "01h 12m 02s";
	private static @Nullable TextLine playtimeLine;
	private static long playtimeKey;

	/**
	 * A card's picture, centred on ({@code centerX}, {@code centerY}) in a card {@code w} wide at scale {@code k}: 56 tall and
	 * at most 70% of the width. Nametags shows its tag as a small label (half the card's width, 36 tall at most) and Playtime its
	 * time in Minecraft's font; every other mod its white picture in {@code ink}.
	 */
	static void drawCardPicture(Canvas c, Catalog.Mod mod, float centerX, float centerY, float w, float k, int ink, boolean on) {
		if (mod.key().equals(NAMETAGS) && com.flintclient.client.render.ModIcons.drawArt(c, on ? "nametags_enabled" : "nametags_disabled",
			c.px(centerX), c.px(centerY), c.px(w * 0.5F), c.px(d(36) * k))) {
			return;
		}
		if (mod.key().equals(PLAYTIME)) {
			drawPixelWords(c, centerX, centerY, w * 0.7F, d(56) * k, ink);
			return;
		}
		drawModIcon(c, mod, centerX, centerY, w * 0.7F, d(56) * k, ink);
	}

	/** Playtime's sample time in Minecraft's font, at the largest whole pixel size that fits {@code maxW} and half of {@code maxH}. */
	private static void drawPixelWords(Canvas c, float centerX, float centerY, float maxW, float maxH, int ink) {
		int base = MenuStyle.pixelScale();
		int fitW = c.px(maxW);
		int fitH = c.px(maxH) / 2;
		long key = ((long) base << 40) | ((long) fitW << 20) | fitH;
		TextLine line = playtimeLine;
		if (line == null || key != playtimeKey) {
			// The largest whole pixel size that fits, from three times the menu's own text size down.
			int s = base * 3;
			while (s > 1 && (8 * s > fitH || c.width(new TextLine(PixelFace.fixed(s), PLAYTIME_SAMPLE, 0)) > fitW)) {
				s--;
			}
			line = new TextLine(PixelFace.fixed(s), PLAYTIME_SAMPLE, 0);
			playtimeLine = line;
			playtimeKey = key;
		}
		int width = c.width(line);
		c.text(line, c.px(centerX) - width / 2, c.baselineFor(line, c.px(centerY)), ink);
	}

	/** A mod's card icon (picked for what it does), else its line icon; also its settings page's badge. */
	static void drawModIcon(Canvas c, Catalog.Mod mod, float centerX, float centerY, float size, int ink) {
		drawModIcon(c, mod, centerX, centerY, size, size * 0.92F, ink);
	}

	/**
	 * A mod's picture from the pack, trimmed to its art and fitted inside {@code maxW × maxH} (layout units) keeping its
	 * shape, so a wide picture (a bar, a label) runs wider rather than shrinking; else its drawn icon, {@code maxH} tall.
	 */
	static void drawModIcon(Canvas c, Catalog.Mod mod, float centerX, float centerY, float maxW, float maxH, int ink) {
		int hPx = Math.max(1, c.px(maxH));
		if (com.flintclient.client.render.ModIcons.draw(c, mod.key(), c.px(centerX), c.px(centerY), Math.max(hPx, c.px(maxW)), hPx, ink)) {
			return;
		}
		int art = com.flintclient.client.render.CardIcons.index(mod.key());
		if (art < 0) {
			MenuStyle.icon(c, mod.icon(), centerX, centerY, maxH, ink);
			return;
		}
		int px = Math.max(1, c.px(maxH));
		c.cardIcon(art, c.px(centerX) - px / 2, c.px(centerY) - px / 2, px, ink);
	}

	/**
	 * The card's status, as Dawn's: Enabled (the green at 40%, green text) or Disabled (the text at 5%, grey text) switches
	 * its Flint module; Coming soon and Unavailable are shown, not pressed. Also on the mod's settings page.
	 */
	static final class Status extends Pressable {
		private final Catalog.Mod mod;
		private final Rows.@Nullable Enabled access;
		private final Label onLabel = new Label(Type.CARD_BUTTON, "Enabled");
		private final Label offLabel = new Label(Type.CARD_BUTTON, "Disabled");
		private final Label fixed;
		private float t = -1;

		Status(Catalog.Mod mod) {
			super(null);
			this.mod = mod;
			boolean switchable = mod.module() != null && mod.module().status().usable();
			this.access = switchable ? new Rows.Enabled(mod.module()) : null;
			this.fixed = new Label(Type.CARD_BUTTON, mod.module() == null && mod.unavailable() == null ? "Coming soon" : "Unavailable");
			name(mod.name() + " on or off");
			if (switchable) {
				setAction(() -> access.set(!on()));
			} else {
				setDisabled(mod.unavailable() != null ? mod.unavailable() : mod.module() == null ? "Flint doesn't have this mod yet" : mod.module().statusNote());
			}
		}

		boolean on() {
			return access != null && (Boolean) access.get();
		}

		String word() {
			return access == null ? fixed.text().toLowerCase(Locale.ROOT) : on() ? "enabled" : "disabled";
		}

		float hoverAmount() {
			return hover;
		}

		@Override
		public String narration() {
			return withReason(mod.name() + ", " + word() + (access != null ? ", switch" : ""));
		}

		@Override
		protected void paint(Canvas c) {
			float rad = Math.min(MenuStyle.radius(), h / 2);
			if (access == null) {
				MenuStyle.box(c, x, y, w, h, rad, MenuStyle.SOFT);
				MenuStyle.centered(c, fixed, x + w / 2, y + h / 2, MenuStyle.NEUTRAL, w - d(16));
				return;
			}
			t = MenuStyle.ease(t, on());
			int fill = Palette.lerp(MenuStyle.STATUS_OFF, MenuStyle.ENABLED, t);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(fill, 0xFF000000, press * 0.15F));
			MenuStyle.halo(c, x, y, w, h, rad, hover, Palette.lerp(Palette.withAlpha(MenuStyle.TEXT, 0.03F), Palette.withAlpha(MenuStyle.SUCCESS, 0.30F), t));
			Label shownLabel = t >= 0.5F ? onLabel : offLabel;
			int ink = Palette.lerp(Palette.lerp(MenuStyle.STATUS_OFF_TEXT, MenuStyle.TEXT, hover * 0.5F), MenuStyle.ENABLED_TEXT, t);
			MenuStyle.centered(c, shownLabel, x + w / 2, y + h / 2, ink, w - d(16));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** The favourite heart, 32 × 32, turning the accent when on (saved under {@link Catalog.Mod#favoriteKey()}). */
	private final class Star extends Pressable {
		private final Catalog.Mod mod;

		Star(Catalog.Mod mod) {
			super(null);
			this.mod = mod;
			name("Favourite " + mod.name());
			setAction(() -> {
				ControlState.settings().toggleFavorite(mod.favoriteKey());
				if (favoritesOnly) {
					refilter();
				}
			});
		}

		private boolean on() {
			return ControlState.settings().isFavorite(mod.favoriteKey());
		}

		float hoverAmount() {
			return hover;
		}

		@Override
		public String narration() {
			return accessibleName() + (on() ? ", on" : ", off");
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.button(c, x, y, w, h, false, hover, press);
			float rad = Math.min(MenuStyle.radius(), h / 2);
			Glyph.HEART.draw(c, x + w / 2, y + h / 2, h * 0.5F, on() ? MenuStyle.STAR_ON : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}

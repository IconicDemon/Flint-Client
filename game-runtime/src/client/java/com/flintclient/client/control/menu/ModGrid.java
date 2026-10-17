package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FilledIcon;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The module grid: cards in catalogue order (row by row; four columns on a wide window, three on a narrower one), or,
 * in the compact view, two columns of rows (icon, name, star, gear and the on/off button). Only the cards the chip, the
 * favourites filter and the search let through show; the search also finds a module by its settings' names, and the card
 * then says which setting matched. Switching the view fades the grid in. It is the content of a scroll view.
 */
final class ModGrid extends Node {
	static final float CARD_H = 426;
	static final float GAP_X = 32;
	static final float GAP_Y = 31;
	static final float END_PAD = 40;
	static final float ROW_H = 112;
	private static final float FADE_TAU = 0.06F;

	private final MenuState state;
	/** Opens a module's settings page (for cards backed by a real module). */
	private final Consumer<String> openSettings;
	private final List<Card> cards = new ArrayList<>();
	private int shown;
	private boolean compact = ControlState.settings().compactView();
	private float fade = 1;

	ModGrid(MenuState state, Consumer<String> openSettings) {
		this.state = state;
		this.openSettings = openSettings;
		for (int i = 0; i < MenuCatalog.ENTRIES.size(); i++) {
			cards.add(add(new Card(i)));
		}
		refilter();
	}

	/** Shows the cards matching the chip, the search and the favourites filter; returns how many. */
	int refilter() {
		shown = 0;
		for (Card card : cards) {
			boolean match = MenuCatalog.matchesDeep(card.entry, state.category, state.query) && (!state.favoritesOnly || state.favorite(card.index));
			card.setVisible(match);
			String why = match ? MenuCatalog.settingMatch(card.entry, state.query) : null;
			card.reason.set(why == null ? "" : why);
			if (match) {
				shown++;
			}
		}
		invalidate();
		return shown;
	}

	int shown() {
		return shown;
	}

	boolean compact() {
		return compact;
	}

	/** Cards or compact rows (saved in Flint's settings); the grid fades in. */
	void setCompact(boolean value) {
		if (compact != value) {
			compact = value;
			ControlState.settings().setCompactView(value);
			fade = 0;
			invalidate();
		}
	}

	private int columns(float width) {
		return compact ? 2 : width >= r(1900) ? 4 : 3;
	}

	private float rowHeight() {
		return compact ? r(ROW_H) : r(CARD_H);
	}

	private float gapY() {
		return compact ? r(18) : r(GAP_Y);
	}

	@Override
	public float prefHeight(float width) {
		int cols = columns(width);
		int rows = (shown + cols - 1) / cols;
		return rows == 0 ? 0 : rows * rowHeight() + (rows - 1) * gapY() + r(END_PAD);
	}

	@Override
	protected void layout() {
		int cols = columns(w);
		float gapX = compact ? r(24) : r(GAP_X);
		float cw = (w - (cols - 1) * gapX) / cols;
		int i = 0;
		for (Card card : cards) {
			if (!card.visible()) {
				continue;
			}
			int col = i % cols;
			int row = i / cols;
			card.setBounds(x + col * (cw + gapX), y + row * (rowHeight() + gapY()), cw, rowHeight());
			i++;
		}
	}

	@Override
	protected void paintChildren(Canvas c) {
		fade = Kit.approach(fade, 1, FADE_TAU);
		float previous = c.opacity();
		c.setOpacity(previous * (0.15F + 0.85F * fade));
		try {
			super.paintChildren(c);
		} finally {
			c.setOpacity(previous);
		}
	}

	/** One module: star, NEW badge, icon, name, the Options row and the Enabled/Disabled row. */
	final class Card extends Node {
		final MenuCatalog.Entry entry;
		private final int index;
		private final Label name;
		private final Label badge = new Label(Type.BADGE, "NEW");
		/** Which setting the search found (empty when the name matched). */
		final Label reason = new Label(Type.CAPTION, "");
		private final Star star;
		private final OptionsPart options;
		private final OptionsPart gear;
		private final StateButton state;
		private float hoverCard = -1;
		private float onness = -1;

		Card(int index) {
			this.index = index;
			this.entry = MenuCatalog.ENTRIES.get(index);
			this.name = new Label(Type.NAME, entry.name());
			star = add(new Star());
			options = add(new OptionsPart(false));
			gear = add(new OptionsPart(true));
			state = add(new StateButton());
			name(entry.name());
		}

		private boolean on() {
			return ModGrid.this.state.enabled(index);
		}

		@Override
		protected void layout() {
			float s = r(36);
			if (compact) {
				// Icon and name at the left; star, gear and the on/off button at the right.
				float bh = r(58);
				float by = y + (h - bh) / 2;
				float stateW = r(190);
				state.setBounds(x + w - r(22) - stateW, by, stateW, bh);
				gear.setBounds(state.x() - r(12) - r(70), by, r(70), bh);
				star.setBounds(gear.x() - r(10) - s - r(8), y + (h - s) / 2, s + r(8), s);
				options.setBounds(x, y, 0, 0);
				options.setVisible(false);
				return;
			}
			options.setVisible(true);
			star.setBounds(x + r(38.5F) - s / 2, y + r(42.5F) - s / 2, s, s);
			float inset = r(19);
			float rowW = w - 2 * inset;
			float gearW = r(82);
			options.setBounds(x + inset, y + r(264), rowW - gearW, r(70));
			gear.setBounds(x + inset + rowW - gearW, y + r(264), gearW, r(70));
			state.setBounds(x + inset, y + r(342), rowW, r(66));
		}

		/** Whether the pointer is on this card or one of its controls. */
		private boolean pointed() {
			KitScreen screen = screen();
			Node over = screen != null ? screen.hovered() : null;
			for (Node n = over; n != null; n = n.parent()) {
				if (n == this) {
					return true;
				}
			}
			return screen != null && screen.captured() == null && containsPoint(screen.mouseX(), screen.mouseY());
		}

		@Override
		protected void paint(Canvas c) {
			hoverCard = MenuControls.ease(hoverCard, pointed());
			onness = MenuControls.ease(onness, on());
			float rad = r(compact ? 20 : 26);
			MenuStyle.framed(c, x, y, w, h, rad, Palette.lerp(MenuStyle.CARD, MenuStyle.CARD_HOVER, hoverCard),
				Palette.lerp(MenuStyle.CARD_EDGE, MenuStyle.CARD_EDGE_HOVER, hoverCard));
			if (compact) {
				paintRow(c);
				return;
			}
			if (entry.isNew()) {
				float bw = badge.width(c) + 2 * r(16.5F);
				float bx = x + w - r(21) - bw;
				float by = y + r(23);
				MenuStyle.box(c, bx, by, bw, r(35), r(10), MenuStyle.BADGE);
				MenuStyle.text(c, badge, bx + r(16.5F), by + r(17.5F), MenuStyle.BADGE_TEXT);
			}
			MenuStyle.icon(c, entry.icon(), x + w / 2, y + r(120), r(88), Palette.lerp(MenuStyle.ICON_OFF, MenuStyle.ICON_ON, onness));
			MenuStyle.centered(c, name, x + w / 2, y + r(218), Palette.lerp(MenuStyle.NAME_OFF, MenuStyle.NAME_ON, onness), w - r(40));
			if (!reason.text().isEmpty()) {
				MenuStyle.centered(c, reason, x + w / 2, y + r(246), MenuStyle.ACCENT, w - r(40));
			}
		}

		/** The compact row: icon, name (and the matched setting under it), the NEW badge after the name. */
		private void paintRow(Canvas c) {
			float cy = y + h / 2;
			MenuStyle.icon(c, entry.icon(), x + r(52), cy, r(48), Palette.lerp(MenuStyle.ICON_OFF, MenuStyle.ICON_ON, onness));
			float left = x + r(100);
			float room = star.x() - r(16) - left;
			boolean two = !reason.text().isEmpty();
			int ink = Palette.lerp(MenuStyle.NAME_OFF, MenuStyle.NAME_ON, onness);
			MenuStyle.fitted(c, name, left, two ? cy - r(14) : cy, ink, room);
			if (two) {
				MenuStyle.fitted(c, reason, left, cy + r(20), MenuStyle.ACCENT, room);
			}
			if (entry.isNew()) {
				float nameW = Math.min(name.width(c), room);
				float bw = badge.width(c) + 2 * r(12);
				float bx = left + nameW + r(14);
				if (bx + bw < star.x() - r(8)) {
					MenuStyle.box(c, bx, (two ? cy - r(14) : cy) - r(16), bw, r(32), r(9), MenuStyle.BADGE);
					MenuStyle.text(c, badge, bx + r(12), two ? cy - r(14) : cy, MenuStyle.BADGE_TEXT);
				}
			}
		}

		/** The favourite star in the top-left corner: outlined, or filled white once favourited. */
		private final class Star extends Pressable {
			Star() {
				super(null);
				setAction(() -> {
					ModGrid.this.state.toggleFavorite(index);
					if (ModGrid.this.state.favoritesOnly) {
						refilter();
					}
				});
				name(entry.name() + " favourite");
			}

			@Override
			public String narration() {
				return entry.name() + " favourite, " + (ModGrid.this.state.favorite(index) ? "on" : "off");
			}

			@Override
			protected void paint(Canvas c) {
				float size = r(25) * (1 - 0.08F * press);
				int px = Math.max(1, c.px(size));
				int x0 = c.px(x + w / 2) - px / 2;
				int y0 = c.px(y + h / 2) - px / 2;
				if (ModGrid.this.state.favorite(index)) {
					c.filledIcon(FilledIcon.STAR, x0, y0, px, MenuStyle.STAR_ON);
				} else {
					c.icon(Icon.STAR, x0, y0, px, Palette.lerp(MenuStyle.STAR, MenuStyle.STAR_HOVER, hover));
				}
				MenuStyle.focusRing(c, x, y, w, h, r(10), focus);
			}
		}

		/** Half of the Options row: the "OPTIONS" text part or the gear part; both open the module's settings page. */
		private final class OptionsPart extends Pressable {
			private final boolean isGear;
			private final Label label = new Label(Type.CARD_BUTTON, "OPTIONS");

			OptionsPart(boolean isGear) {
				super(null);
				this.isGear = isGear;
				String module = MenuCatalog.moduleOf(entry);
				setAction(() -> openSettings.accept(module));
				name(entry.name() + (isGear ? " settings" : " options"));
			}

			@Override
			protected void paint(Canvas c) {
				int fill = Palette.lerp(Palette.lerp(MenuStyle.OPTIONS, MenuStyle.OPTIONS_HOVER, hover), MenuStyle.OPTIONS_PRESS, press);
				int x0 = c.px(x);
				int y0 = c.px(y);
				int pw = c.px(x + w) - x0;
				int ph = c.px(y + h) - y0;
				int rad = c.px(MenuStyle.round(r(14)));
				// Alone in the compact row the gear is a whole button; on a card it is the Options row's right end.
				int corners = compact ? Canvas.TOP_LEFT | Canvas.TOP_RIGHT | Canvas.BOTTOM_LEFT | Canvas.BOTTOM_RIGHT
					: isGear ? Canvas.TOP_RIGHT | Canvas.BOTTOM_RIGHT : Canvas.TOP_LEFT | Canvas.BOTTOM_LEFT;
				c.roundRectCorners(x0, y0, pw, ph, rad, fill, corners);
				int ink = Palette.lerp(MenuStyle.OPTIONS_TEXT, 0xFFFFFFFF, hover * 0.5F);
				if (isGear) {
					if (!compact) {
						c.fill(x0, y0, x0 + MenuStyle.edge(), y0 + ph, MenuStyle.OPTIONS_RULE);
					}
					MenuStyle.icon(c, Icon.GEAR, x + w / 2, y + h / 2, r(28) * (1 - 0.06F * press), ink);
				} else {
					MenuStyle.centered(c, label, x + w / 2, y + h / 2, ink, w - r(16));
				}
				MenuStyle.focusRing(c, x, y, w, h, r(14), focus);
			}
		}

		/**
		 * ENABLED (ember outline) or DISABLED (muted): turns the module on or off. A module that isn't built (or can't be)
		 * says so instead, and opens its page, which explains why.
		 */
		private final class StateButton extends Pressable {
			private final Label enabled = new Label(Type.CARD_BUTTON, "ENABLED");
			private final Label disabled = new Label(Type.CARD_BUTTON, "DISABLED");
			private final Label notBuilt = new Label(Type.CARD_BUTTON, "NOT BUILT YET");
			private final Label blocked = new Label(Type.CARD_BUTTON, "UNAVAILABLE");

			StateButton() {
				super(null);
				setAction(() -> {
					if (ModGrid.this.state.usable(index)) {
						ModGrid.this.state.toggleEnabled(index);
					} else {
						openSettings.accept(MenuCatalog.moduleOf(entry));
					}
				});
				name(entry.name() + " enabled");
			}

			@Override
			public String narration() {
				if (!ModGrid.this.state.usable(index)) {
					return entry.name() + ", " + (MenuState.module(index).descriptor().status()
						== com.flintclient.client.control.model.ModuleDescriptor.Status.BLOCKED ? "unavailable" : "not built yet") + ", button";
				}
				return entry.name() + ", switch, " + (on() ? "enabled" : "disabled");
			}

			@Override
			protected void paint(Canvas c) {
				float t = onness;
				int off = Palette.lerp(MenuStyle.DISABLED, MenuStyle.DISABLED_HOVER, hover);
				int onFill = Palette.lerp(MenuStyle.ENABLED, MenuStyle.ENABLED_HOVER, hover);
				int fill = Palette.lerp(Palette.lerp(off, onFill, t), 0xFF000000, press * 0.15F);
				float rad = r(14);
				MenuStyle.box(c, x, y, w, h, rad, fill);
				if (t > 0.01F) {
					MenuStyle.ring(c, x, y, w, h, rad, MenuStyle.edge(), Palette.withAlpha(MenuStyle.ENABLED_EDGE, t));
				}
				Label shown = on() ? enabled : disabled;
				if (!ModGrid.this.state.usable(index)) {
					shown = MenuState.module(index).descriptor().status() == com.flintclient.client.control.model.ModuleDescriptor.Status.BLOCKED
						? blocked : notBuilt;
				}
				int ink = Palette.lerp(MenuStyle.DISABLED_TEXT, MenuStyle.ENABLED_TEXT, t);
				MenuStyle.centered(c, shown, x + w / 2, y + h / 2, ink, w - r(16));
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			}
		}
	}
}

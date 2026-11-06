package com.flintclient.client.ui.screen;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.widget.AccountPill;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.RewardsCard;
import com.flintclient.client.ui.widget.MenuToggle;
import com.flintclient.client.ui.widget.ServerSlot;
import com.flintclient.client.ui.widget.WidgetVisibility;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.Nullable;

/**
 * Places the main menu's parts for any window size and GUI scale, in GUI units (whole units, so every
 * visible edge lands on a pixel and hit areas match what's drawn).
 *
 * <p>The composition follows the reference design: the logo above a centred column of five buttons,
 * Quit Game set apart below with the version line, the account row top-right, the menu switch top-left (both close to the corners),
 * the Quick Play rail on the left edge (only when servers are pinned, one to five) and the "What's new" card bottom-right with
 * "Daily rewards" above it. Gaps shrink when the window is short; the rail and the cards give way (narrower, then hidden) rather
 * than ever overlapping the menu. Minecraft's own title screen places its switch and cards with the same rules
 * ({@link VanillaTitleExtras}), so they stay put when the player changes menus.
 */
final class MenuLayout {
	static final int BUTTON_W = 96;
	static final int BUTTON_H = 15;
	static final int QUIT_W = 66;
	static final int QUIT_H = 13;
	static final int ICON_BUTTON = 13;
	static final int SLOT = 13;
	private static final int TOP_GAP = 3;
	private static final int RAIL_PAD = 3;
	private static final int RAIL_GAP = 4;
	private static final int CARD_W = 88;
	private static final int CARD_H = 33;
	private static final int CARD_MIN_W = 64;
	/** Between the "Daily rewards" card and "What's new" under it. */
	private static final int CARD_GAP = 5;

	final List<MenuButton> column = new ArrayList<>();
	MenuButton quit;
	AccountPill account;
	/** The menu choice (Flint or Vanilla), top-left. */
	MenuToggle menuSwitch;
	List<IconButton> topIcons = List.of();
	List<ServerSlot> slots = List.of();
	@Nullable NewsCard news;
	@Nullable RewardsCard rewards;

	/** Computed positions, read by the screen when drawing. */
	float markHeight = 15;
	/** The top bar's bottom edge (toasts start below it). */
	int topBarBottom;
	int logoY;
	int footerY;
	boolean railVisible;
	int railX;
	int railY;
	int railW;
	int railH;

	/** The top bar's distance from the top and from the sides: close to the corners. */
	static int barTop(int height) {
		return Math.clamp(Math.round(height * 0.028F), 6, 11);
	}

	static int barSide(int width) {
		return Math.clamp(Math.round(width * 0.018F), 6, 12);
	}

	/** Where a top-left switch goes and how big it is, in menu units. */
	static int[] switchSpot(MenuToggle toggle, int width, int height) {
		int w = toggle.preferredWidth(Canvas.measuring(com.flintclient.client.ui.MenuScale.pixels()));
		return new int[] {barSide(width), barTop(height), w, Math.round(MenuToggle.HEIGHT)};
	}

	/** Where a card goes, and whether it shows there. */
	interface Spot {
		void place(int x, int y, int w, int h, boolean shown);
	}

	/**
	 * "What's new" at the bottom right and "Daily rewards" above it (or alone there), in menu units: kept {@code clearRight}
	 * units right of anything in the middle, and above Minecraft's copyright line (which its own title screen draws along the
	 * bottom), narrowing and then hiding rather than coming closer.
	 */
	static void placeCards(@Nullable Spot news, @Nullable Spot rewards, int width, int height, int clearRight, int topBarBottom) {
		int marginSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
		int copyright = (int) Math.ceil(11.0F * com.flintclient.client.platform.Frame.windowGuiScale() / com.flintclient.client.ui.MenuScale.pixels()) + 3;
		int marginBottom = Math.max(Math.clamp(Math.round(height * 0.066F), 8, 18), copyright);
		int cardW = Math.min(CARD_W, width - marginSide - clearRight);
		int cardY = height - marginBottom - CARD_H;
		if (news != null) {
			news.place(width - marginSide - Math.max(cardW, 1), cardY, Math.max(cardW, 1), CARD_H, cardW >= CARD_MIN_W && cardY >= topBarBottom + 8);
			cardY -= CARD_H + CARD_GAP;
		}
		if (rewards != null) {
			rewards.place(width - marginSide - Math.max(cardW, 1), cardY, Math.max(cardW, 1), CARD_H, cardW >= CARD_MIN_W && cardY >= topBarBottom + 8);
		}
	}

	void apply(int width, int height) {
		int marginTop = barTop(height);
		int marginSide = barSide(width);
		int marginBottom = Math.clamp(Math.round(height * 0.066F), 8, 18);
		int topBarBottom = marginTop + ICON_BUTTON;
		this.topBarBottom = topBarBottom;

		// Vertical stack: logo, five buttons (Store slightly apart), Quit Game, version line.
		int logoGap = 14;
		int buttonGap = 4;
		int storeGap = 6;
		int quitGap = 20;
		int footerGap = 9;
		int available = height - topBarBottom - 6 - marginBottom;
		int total = stackHeight(logoGap, buttonGap, storeGap, quitGap, footerGap);
		if (total > available) {
			// Short windows: tighten the generous gaps first, never the controls themselves.
			logoGap = 8;
			quitGap = 10;
			storeGap = 4;
			buttonGap = 3;
			footerGap = 7;
			total = stackHeight(logoGap, buttonGap, storeGap, quitGap, footerGap);
		}
		int top = Math.round(height * 0.54F - total / 2.0F);
		top = Math.max(top, topBarBottom + 6);
		top = Math.min(top, Math.max(topBarBottom + 6, height - marginBottom - total));

		logoY = top;
		int columnX = (width - BUTTON_W) / 2;
		int y = top + Math.round(markHeight) + logoGap;
		for (int i = 0; i < column.size(); i++) {
			if (i == column.size() - 1) {
				y += storeGap - buttonGap;
			}
			place(column.get(i), columnX, y, BUTTON_W, BUTTON_H);
			y += BUTTON_H + buttonGap;
		}
		y += quitGap - buttonGap;
		place(quit, (width - QUIT_W) / 2, y, QUIT_W, QUIT_H);
		footerY = y + QUIT_H + footerGap;

		// Top-right row: account pill, then four icon buttons, right-aligned.
		int x = width - marginSide;
		for (int i = topIcons.size() - 1; i >= 0; i--) {
			x -= ICON_BUTTON;
			place(topIcons.get(i), x, marginTop, ICON_BUTTON, ICON_BUTTON);
			x -= TOP_GAP;
		}
		int pillW = account.preferredWidth(Canvas.measuring(com.flintclient.client.ui.MenuScale.pixels()));
		place(account, x - pillW, marginTop, pillW, Math.round(AccountPill.HEIGHT));

		// Top-left: the menu switch, Flint and Vanilla side by side.
		int[] spot = switchSpot(menuSwitch, width, height);
		place(menuSwitch, spot[0], spot[1], spot[2], spot[3]);

		// Left rail, vertically centred; hidden if it would come within 8 units of the column.
		railW = SLOT + 2 * RAIL_PAD;
		railH = slots.size() * SLOT + (slots.size() - 1) * RAIL_GAP + 2 * RAIL_PAD;
		railX = Math.clamp(Math.round(width * 0.013F), 4, 8);
		railY = Math.round(height / 2.0F - railH / 2.0F);
		// Quick Play: no pinned servers, no rail at all (no panel, no placeholders).
		railVisible = !slots.isEmpty() && railX + railW + 8 <= columnX && railY >= topBarBottom + 4 && railY + railH <= height - 4;
		for (int i = 0; i < slots.size(); i++) {
			ServerSlot slot = slots.get(i);
			WidgetVisibility.set(slot, railVisible);
			place(slot, railX + RAIL_PAD, railY + RAIL_PAD + i * (SLOT + RAIL_GAP), SLOT, SLOT);
		}

		// "What's new" card, bottom-right, and "Daily rewards" above it; they narrow, then hide, rather than touch the column.
		placeCards(news == null ? null : spot(news), rewards == null ? null : spot(rewards), width, height, columnX + BUTTON_W + 8, topBarBottom);
	}

	private static Spot spot(AbstractWidget widget) {
		return (x, y, w, h, shown) -> {
			WidgetVisibility.set(widget, shown);
			place(widget, x, y, w, h);
		};
	}

	private int stackHeight(int logoGap, int buttonGap, int storeGap, int quitGap, int footerGap) {
		int buttons = column.size() * BUTTON_H + (column.size() - 2) * buttonGap + storeGap;
		return Math.round(markHeight) + logoGap + buttons + quitGap + QUIT_H + footerGap;
	}

	private static void place(AbstractWidget widget, int x, int y, int w, int h) {
		widget.setRectangle(w, h, x, y);
	}
}

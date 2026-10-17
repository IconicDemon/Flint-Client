package com.flintclient.client.ui.screen;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.widget.AccountPill;
import com.flintclient.client.ui.widget.IconButton;
import com.flintclient.client.ui.widget.MenuButton;
import com.flintclient.client.ui.widget.NewsCard;
import com.flintclient.client.ui.widget.ServerSlot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import org.jspecify.annotations.Nullable;

/**
 * Places the main menu's parts for any window size and GUI scale, in GUI units (whole units, so every
 * visible edge lands on a pixel and hit areas match what's drawn).
 *
 * <p>The composition follows the reference design: the logo above a centred column of five buttons,
 * Quit Game set apart below with the version line, the account row top-right, the Quick Play rail on the
 * left edge (only when servers are pinned, one to five) and the "What's new" card bottom-right. Gaps shrink when the window is short; the rail and
 * the card give way (narrower, then hidden) rather than ever overlapping the menu.
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

	final List<MenuButton> column = new ArrayList<>();
	MenuButton quit;
	AccountPill account;
	List<IconButton> topIcons = List.of();
	List<ServerSlot> slots = List.of();
	@Nullable NewsCard news;

	/** Computed positions, read by the screen when drawing. */
	float markHeight = 15;
	int logoY;
	int footerY;
	boolean railVisible;
	int railX;
	int railY;
	int railW;
	int railH;

	void apply(int width, int height) {
		int marginTop = Math.clamp(Math.round(height * 0.05F), 8, 16);
		int marginSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
		int marginBottom = Math.clamp(Math.round(height * 0.066F), 8, 18);
		int topBarBottom = marginTop + ICON_BUTTON;

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
		int pillW = account.preferredWidth(Canvas.measuring());
		place(account, x - pillW, marginTop, pillW, Math.round(AccountPill.HEIGHT));

		// Left rail, vertically centred; hidden if it would come within 8 units of the column.
		railW = SLOT + 2 * RAIL_PAD;
		railH = slots.size() * SLOT + (slots.size() - 1) * RAIL_GAP + 2 * RAIL_PAD;
		railX = Math.clamp(Math.round(width * 0.013F), 4, 8);
		railY = Math.round(height / 2.0F - railH / 2.0F);
		// Quick Play: no pinned servers, no rail at all (no panel, no placeholders).
		railVisible = !slots.isEmpty() && railX + railW + 8 <= columnX && railY >= topBarBottom + 4 && railY + railH <= height - 4;
		for (int i = 0; i < slots.size(); i++) {
			ServerSlot slot = slots.get(i);
			slot.visible = railVisible;
			place(slot, railX + RAIL_PAD, railY + RAIL_PAD + i * (SLOT + RAIL_GAP), SLOT, SLOT);
		}

		// "What's new" card, bottom-right; narrows, then hides, rather than touch the column.
		if (news != null) {
			int columnRight = columnX + BUTTON_W + 8;
			int cardW = Math.min(CARD_W, width - marginSide - columnRight);
			int cardY = height - marginBottom - CARD_H;
			news.visible = cardW >= CARD_MIN_W && cardY >= topBarBottom + 8;
			place(news, width - marginSide - cardW, cardY, Math.max(cardW, 1), CARD_H);
		}
	}

	private int stackHeight(int logoGap, int buttonGap, int storeGap, int quitGap, int footerGap) {
		int buttons = column.size() * BUTTON_H + (column.size() - 2) * buttonGap + storeGap;
		return Math.round(markHeight) + logoGap + buttons + quitGap + QUIT_H + footerGap;
	}

	private static void place(AbstractWidget widget, int x, int y, int w, int h) {
		widget.setRectangle(w, h, x, y);
	}
}

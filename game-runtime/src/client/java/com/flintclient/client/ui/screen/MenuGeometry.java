package com.flintclient.client.ui.screen;

/**
 * The main menu's geometry for a window and the sizes the player picked for its parts (Utilities › Menu sizes), in menu units:
 * plain arithmetic, no Minecraft, so the tests can run it for every window ({@link MenuLayout} measures the widgets and applies
 * the result to them).
 *
 * <p>Each part grows or shrinks about its own anchor: the logo, the buttons and the version line about the middle, the switch from
 * the top-left corner, the account and its icons from the top-right one, the cards from the bottom-right. The bands they live in
 * never overlap: the top bar across the top, the centre stack below it, the cards right of the stack and under the bar, Quick
 * Play left of the stack. A part that has no room at the size asked gives way, smaller (never under what was asked of it and 100 %,
 * the cards then hide, as they always did), so nothing overlaps and nothing leaves the screen. At 100 % everything is where it
 * always was, unit for unit.
 */
final class MenuGeometry {
	static final int BUTTON_W = 96;
	static final int BUTTON_H = 15;
	static final int QUIT_W = 66;
	static final int QUIT_H = 13;
	static final int ICON_BUTTON = 13;
	/** The top bar's height: the switch, the account pill and the icon buttons. */
	static final int BAR_H = 13;
	static final int SLOT = 13;
	static final float MARK_HEIGHT = 15.0F;
	static final int TOP_GAP = 3;
	static final int RAIL_PAD = 3;
	static final int RAIL_GAP = 4;
	static final int CARD_W = 88;
	static final int CARD_H = 33;
	static final int CARD_MIN_W = 64;
	/** Between the "Daily rewards" card and "What's new" under it. */
	static final int CARD_GAP = 5;
	/** The "Join our Discord" card above Daily Rewards: as wide as the cards, a little taller (the player stands in it). */
	static final int DISCORD_H = 42;
	/** How far the player on the Discord card stands out above it (room kept free above the card). */
	static final int DISCORD_POP = 12;
	/** The showcase at the bottom left: a 16:9 picture and the thin bars under it. */
	static final int SHOW_W = 100;
	static final int SHOW_H = 60;
	static final int SHOW_MIN_W = 72;
	/** Kept between the switch and the account, between the stack and the window's sides, and right of the stack. */
	static final int CLEAR = 8;
	/** Sizes a part is given way to come in twentieths. */
	private static final float STEPS = 20.0F;
	private static final float EPSILON = 0.0005F;

	/** The parts the player sizes, in {@code GameOptions.MENU_SIZES}' order. */
	enum Part {
		LOGO, BUTTONS, VERSION, REWARDS, NEWS, SWITCH, TOP_RIGHT, DISCORD, SHOWCASE
	}

	/** A width that depends on the size a part is drawn at (text measured at that size), in menu units. */
	interface Measure {
		int at(float scale);
	}

	/** The gaps in the centre stack at 100 %: generous, or tightened for a short window. */
	record Gaps(int logo, int button, int store, int quit, int footer) {
	}

	static final Gaps LOOSE = new Gaps(14, 4, 6, 20, 9);
	static final Gaps TIGHT = new Gaps(8, 3, 4, 10, 7);

	/** What the geometry needs to know: the window, the sizes asked for, and the widths of the parts that hold text. */
	static final class Input {
		int width;
		int height;
		/** Pixels per menu unit: a part is never drawn under one pixel per unit. */
		float unit = 1.0F;
		/** The sizes asked for, by {@link Part} (1: the design size). */
		final float[] asked = filled(Part.values().length);
		Measure logo = s -> Math.round(60 * s);
		Measure footer = s -> Math.round(90 * s);
		Measure pill = s -> Math.round(60 * s);
		Measure toggle = s -> Math.round(70 * s);
		/** The version line's capitals and what hangs below its baseline at 100 %, in menu units. */
		float footerCap = 2.5F;
		float footerDescent = 1.0F;
		int buttons = 5;
		int icons = 4;
		int slots;
		boolean news = true;
		boolean rewards = true;
		/** The "Join our Discord" card (above Daily Rewards), the showcase (bottom left) and other mods' buttons (top right). */
		boolean discord;
		boolean showcase;
		int externals;
		/** The least room under the cards (Minecraft's copyright line), in menu units. */
		int copyright;

		Input size(Part part, float scale) {
			asked[part.ordinal()] = scale;
			return this;
		}
	}

	/** A part's place: x, y, width, height in menu units, the size it is drawn at, and whether it shows. */
	record Box(int x, int y, int w, int h, float scale, boolean shown) {
		int right() {
			return x + w;
		}

		int bottom() {
			return y + h;
		}

		boolean overlaps(Box o) {
			return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
		}
	}

	/** Where everything goes. */
	static final class Result {
		/** The size each part is drawn at, by {@link Part}: what was asked, or less where there was no room. */
		final float[] scale = new float[Part.values().length];
		int topBarBottom;
		Box logo;
		Box[] column;
		Box quit;
		/** The version line's baseline, its box (capitals to descent) and the size it is drawn at. */
		int footerY;
		Box footer;
		Box[] icons;
		Box pill;
		Box toggle;
		boolean railVisible;
		int railX;
		int railY;
		int railW;
		int railH;
		Box news;
		Box rewards;
		/** Null when that part isn't on the menu; hidden ({@code shown} false) when the window has no room for it. */
		Box discord;
		Box showcase;
		/**
		 * Other mods' buttons, inside the top-right row between Settings and Utilities (Utilities stays last), in the order
		 * found, as many as fit.
		 */
		Box[] externals = new Box[0];

		float scale(Part part) {
			return scale[part.ordinal()];
		}

		/** The area a part takes (to show which one a size changes), or null when it isn't shown. */
		Box bounds(Part part) {
			return switch (part) {
				case LOGO -> logo;
				case BUTTONS -> {
					Box first = column.length > 0 ? column[0] : quit;
					int left = Math.min(first.x(), quit.x());
					int right = Math.max(first.right(), quit.right());
					yield new Box(left, first.y(), right - left, quit.bottom() - first.y(), scale(Part.BUTTONS), true);
				}
				case VERSION -> footer;
				case REWARDS -> rewards != null && rewards.shown() ? rewards : null;
				case NEWS -> news != null && news.shown() ? news : null;
				case DISCORD -> discord != null && discord.shown() ? discord : null;
				case SHOWCASE -> showcase != null && showcase.shown() ? showcase : null;
				case SWITCH -> toggle;
				case TOP_RIGHT -> {
					Box last = icons.length > 0 ? icons[icons.length - 1] : pill;
					int h = pill.h();
					for (Box icon : icons) {
						h = Math.max(h, icon.h());
					}
					// Other mods' buttons sit inside the row, so they grow and shrink with it.
					yield new Box(pill.x(), pill.y(), last.right() - pill.x(), h, scale(Part.TOP_RIGHT), true);
				}
			};
		}
	}

	private MenuGeometry() {
	}

	private static float[] filled(int n) {
		float[] out = new float[n];
		java.util.Arrays.fill(out, 1.0F);
		return out;
	}

	/** The top bar's distance from the top and from the sides: close to the corners. */
	static int barTop(int height) {
		return Math.clamp(Math.round(height * 0.028F), 6, 11);
	}

	static int barSide(int width) {
		return Math.clamp(Math.round(width * 0.018F), 6, 12);
	}

	/** {@code units} × {@code scale}, rounded: a design length at a part's size. */
	static int scaled(float units, float scale) {
		return Math.round(units * scale);
	}

	/** The least size a part is drawn at in a window of {@code unit} pixels per menu unit (never under a pixel per unit). */
	static float least(float unit) {
		return unit > 0.0F ? 1.0F / unit : 1.0F;
	}

	/** Where a top-left switch goes: its corner, at {@code scale} (Minecraft's title screen has nothing else up there). */
	static Box switchBox(Measure toggle, int width, int height, float scale) {
		return new Box(barSide(width), barTop(height), toggle.at(scale), scaled(BAR_H, scale), scale, true);
	}

	static Result compute(Input in) {
		Result out = new Result();
		int width = in.width;
		int height = in.height;
		float floor = least(in.unit);
		float[] asked = new float[in.asked.length];
		for (int i = 0; i < asked.length; i++) {
			asked[i] = Math.max(floor, in.asked[i] > 0.0F ? in.asked[i] : 1.0F);
		}

		// The top bar: the switch at the left, the account and its icons at the right, kept CLEAR apart.
		int marginTop = barTop(height);
		int side = barSide(width);
		float[] bar = {asked[Part.SWITCH.ordinal()], asked[Part.TOP_RIGHT.ordinal()]};
		float[] fitBar = fit(bar, floor, s -> side + estimate(in.toggle, s[0]) + CLEAR <= width - side - rightWidth(in, s[1], true));
		float sw = fitBar[0];
		float st = fitBar[1];
		out.scale[Part.SWITCH.ordinal()] = sw;
		out.scale[Part.TOP_RIGHT.ordinal()] = st;
		int icon = scaled(ICON_BUTTON, st);
		int gap = scaled(TOP_GAP, st);
		int pillW = in.pill.at(st);
		out.toggle = switchBox(in.toggle, width, height, sw);
		// Right to left: the last icon (Utilities), other mods' buttons, the rest of the icons, the account pill. The row is
		// sized without other mods' buttons (it never gets smaller for them); they take what room is left before the switch.
		int tail = Math.min(1, in.icons);
		int shown = externalsThatFit(in.externals, in.icons, width - side, pillW, icon, gap, out.toggle.right() + CLEAR);
		int x = width - side;
		out.icons = new Box[in.icons];
		out.externals = new Box[shown];
		for (int i = in.icons - 1; i >= in.icons - tail; i--) {
			x -= icon;
			out.icons[i] = new Box(x, marginTop, icon, icon, st, true);
			x -= gap;
		}
		for (int i = shown - 1; i >= 0; i--) {
			x -= icon;
			out.externals[i] = new Box(x, marginTop, icon, icon, st, true);
			x -= gap;
		}
		for (int i = in.icons - tail - 1; i >= 0; i--) {
			x -= icon;
			out.icons[i] = new Box(x, marginTop, icon, icon, st, true);
			x -= gap;
		}
		out.pill = new Box(x - pillW, marginTop, pillW, scaled(BAR_H, st), st, true);
		int topBarBottom = marginTop + Math.max(Math.max(icon, out.pill.h()), out.toggle.h());
		out.topBarBottom = topBarBottom;

		// The centre stack: logo, five buttons (Store slightly apart), Quit Game, the version line.
		int marginBottom = Math.clamp(Math.round(height * 0.066F), 8, 18);
		int available = height - topBarBottom - 6 - marginBottom;
		float[] stack = {asked[Part.LOGO.ordinal()], asked[Part.BUTTONS.ordinal()], asked[Part.VERSION.ordinal()]};
		// Short windows: tighten the generous gaps first, then (only for parts made larger) the parts themselves.
		Gaps gaps = need(in, stack, LOOSE) > available ? TIGHT : LOOSE;
		Gaps chosen = gaps;
		float[] fitStack = fit(stack, floor, s -> need(in, s, chosen) <= available && widest(in, s) <= width - 2 * CLEAR);
		float sl = fitStack[0];
		float sb = fitStack[1];
		float sv = fitStack[2];
		out.scale[Part.LOGO.ordinal()] = sl;
		out.scale[Part.BUTTONS.ordinal()] = sb;
		out.scale[Part.VERSION.ordinal()] = sv;
		int total = stackHeight(in, fitStack, gaps);
		int top = Math.round(height * 0.54F - total / 2.0F);
		top = Math.max(top, topBarBottom + 6);
		top = Math.min(top, Math.max(topBarBottom + 6, height - marginBottom - total - hang(in, sv)));

		int logoH = scaled(MARK_HEIGHT, sl);
		int logoW = in.logo.at(sl);
		out.logo = new Box((width - logoW) / 2, top, logoW, logoH, sl, true);
		int buttonW = scaled(BUTTON_W, sb);
		int buttonH = scaled(BUTTON_H, sb);
		int buttonGap = scaled(gaps.button(), sb);
		int columnX = (width - buttonW) / 2;
		int y = top + logoH + scaled(gaps.logo(), (sl + sb) / 2.0F);
		out.column = new Box[in.buttons];
		for (int i = 0; i < in.buttons; i++) {
			if (i == in.buttons - 1) {
				y += scaled(gaps.store(), sb) - buttonGap;
			}
			out.column[i] = new Box(columnX, y, buttonW, buttonH, sb, true);
			y += buttonH + buttonGap;
		}
		y += scaled(gaps.quit(), sb) - buttonGap;
		int quitW = scaled(QUIT_W, sb);
		int quitH = scaled(QUIT_H, sb);
		out.quit = new Box((width - quitW) / 2, y, quitW, quitH, sb, true);
		out.footerY = y + quitH + footerOffset(in, sb, sv, gaps.footer());
		int footerW = in.footer.at(sv);
		int cap = (int) Math.ceil(in.footerCap * sv);
		out.footer = new Box((width - footerW) / 2, out.footerY - cap, footerW, cap + (int) Math.ceil(in.footerDescent * sv), sv, true);
		int stackLeft = Math.min(Math.min(columnX, out.quit.x()), Math.min(out.logo.x(), out.footer.x()));
		int stackRight = Math.max(Math.max(columnX + buttonW, out.quit.right()), Math.max(out.logo.right(), out.footer.right()));

		// Quick Play, vertically centred at the left; hidden if it would come within CLEAR units of the stack.
		out.railW = SLOT + 2 * RAIL_PAD;
		out.railH = in.slots * SLOT + (in.slots - 1) * RAIL_GAP + 2 * RAIL_PAD;
		out.railX = Math.clamp(Math.round(width * 0.013F), 4, 8);
		out.railY = Math.round(height / 2.0F - out.railH / 2.0F);
		out.railVisible = in.slots > 0 && out.railX + out.railW + CLEAR <= stackLeft && out.railY >= topBarBottom + 4
			&& out.railY + out.railH <= height - 4;

		// "What's new" at the bottom right, "Daily rewards" above it: clear of the stack, under the top bar.
		Box[] cards = cards(in.news, in.rewards, in.discord, asked[Part.NEWS.ordinal()], asked[Part.REWARDS.ordinal()], asked[Part.DISCORD.ordinal()],
			in.unit, width, height, stackRight + CLEAR, topBarBottom, in.copyright);
		out.news = cards[0];
		out.rewards = cards[1];
		out.discord = cards[2];
		out.scale[Part.NEWS.ordinal()] = cards[0] != null && cards[0].shown() ? cards[0].scale() : asked[Part.NEWS.ordinal()];
		out.scale[Part.REWARDS.ordinal()] = cards[1] != null && cards[1].shown() ? cards[1].scale() : asked[Part.REWARDS.ordinal()];
		out.scale[Part.DISCORD.ordinal()] = cards[2] != null && cards[2].shown() ? cards[2].scale() : asked[Part.DISCORD.ordinal()];

		// The bottom left: the showcase; left of the stack, clear of Quick Play, at the cards' distance from the edges.
		left(out, in, asked, stackLeft, topBarBottom);
		return out;
	}

	/**
	 * How many of other mods' title-screen buttons fit in the top-right row (as large as its icons, a gap apart), in the order
	 * found: as many as keep the account pill (its left end, the row ending at {@code right}) at {@code limit} or right of it
	 * (the switch and {@link #CLEAR}); the rest are left off. None without the row's own icons to sit among.
	 */
	static int externalsThatFit(int count, int icons, int right, int pillW, int icon, int gap, int limit) {
		if (icons <= 0) {
			return 0;
		}
		int n = 0;
		while (n < count && right - (icons + n + 1) * (icon + gap) - pillW >= limit) {
			n++;
		}
		return n;
	}

	/**
	 * The showcase at the bottom left, its bottom where "What's new" has its own: it narrows, then gets smaller, then hides
	 * rather than come within {@link #CLEAR} of the stack or over Quick Play.
	 */
	private static void left(Result out, Input in, float[] asked, int stackLeft, int topBarBottom) {
		int width = in.width;
		int height = in.height;
		int marginSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
		int bottom = height - Math.max(Math.clamp(Math.round(height * 0.066F), 8, 18), in.copyright);
		int limit = stackLeft - CLEAR;
		boolean rail = out.railVisible;
		int railBottom = out.railY + out.railH;
		float floor = least(in.unit);
		float wanted = Math.max(floor, asked[Part.SHOWCASE.ordinal()]);
		out.scale[Part.SHOWCASE.ordinal()] = wanted;
		if (!in.showcase) {
			return;
		}
		int cardBottom = bottom;
		int ceiling = Math.max(topBarBottom + 8, rail ? railBottom + 6 : 0);
		int room = limit - marginSide;
		float fit = Math.min(room / (float) SHOW_MIN_W, (cardBottom - ceiling) / (float) SHOW_H);
		float s = wanted <= fit ? wanted : (float) Math.floor(fit * STEPS) / STEPS;
		boolean shown = s > 0.0F && s >= Math.min(wanted, 1.0F) - EPSILON && s >= floor - EPSILON;
		if (!shown) {
			s = wanted;
		}
		int w = Math.max(1, Math.min(scaled(SHOW_W, s), Math.max(1, room)));
		int h = scaled(SHOW_H, s);
		out.showcase = new Box(marginSide, cardBottom - h, w, h, s, shown);
		out.scale[Part.SHOWCASE.ordinal()] = s;
	}

	/**
	 * "What's new" at the bottom right and "Daily rewards" above it (or alone there), kept right of {@code clearRight} and above
	 * Minecraft's copyright line ({@code copyright} units), each under {@code topBarBottom} by 8: a card with too little room is
	 * drawn smaller, down to its own size asked or 100 % (whichever is less), then hides. Index 0 is "What's new", 1 "Daily
	 * rewards"; null for a card that isn't there.
	 */
	static Box[] cards(boolean news, boolean rewards, float newsAsked, float rewardsAsked, float unit, int width, int height, int clearRight,
		int topBarBottom, int copyright) {
		return cards(news, rewards, false, newsAsked, rewardsAsked, 1.0F, unit, width, height, clearRight, topBarBottom, copyright);
	}

	/** As above, with the "Join our Discord" card (index 2) above "Daily rewards" (or above whichever card is the top one). */
	static Box[] cards(boolean news, boolean rewards, boolean discord, float newsAsked, float rewardsAsked, float discordAsked, float unit,
		int width, int height, int clearRight, int topBarBottom, int copyright) {
		int marginSide = Math.clamp(Math.round(width * 0.036F), 8, 20);
		int marginBottom = Math.max(Math.clamp(Math.round(height * 0.066F), 8, 18), copyright);
		int room = width - marginSide - clearRight;
		int right = width - marginSide;
		int bottom = height - marginBottom;
		int ceiling = topBarBottom + 8;
		float floor = least(unit);
		Box[] out = new Box[3];
		if (news) {
			out[0] = card(Math.max(floor, newsAsked), floor, room, right, bottom, ceiling, CARD_H);
			if (out[0].shown()) {
				bottom = out[0].y() - scaled(CARD_GAP, (out[0].scale() + Math.max(floor, rewardsAsked)) / 2.0F);
			}
		}
		if (rewards) {
			out[1] = card(Math.max(floor, rewardsAsked), floor, room, right, bottom, ceiling, CARD_H);
			if (out[1].shown()) {
				bottom = out[1].y() - scaled(CARD_GAP, (out[1].scale() + Math.max(floor, discordAsked)) / 2.0F);
			}
		}
		if (discord) {
			out[2] = card(Math.max(floor, discordAsked), floor, room, right, bottom, ceiling + scaled(DISCORD_POP, Math.max(floor, discordAsked)), DISCORD_H);
		}
		return out;
	}

	private static Box card(float asked, float floor, int room, int right, int bottom, int ceiling, int cardH) {
		float limit = Math.min(room / (float) CARD_MIN_W, (bottom - ceiling) / (float) cardH);
		float s = asked <= limit ? asked : (float) Math.floor(limit * STEPS) / STEPS;
		boolean shown = s > 0.0F && s >= Math.min(asked, 1.0F) - EPSILON && s >= floor - EPSILON;
		if (!shown) {
			s = asked;
		}
		int w = Math.max(1, Math.min(scaled(CARD_W, s), Math.max(1, room)));
		int h = scaled(cardH, s);
		return new Box(right - w, bottom - h, w, h, s, shown);
	}

	/**
	 * The largest sizes up to {@code asked} for which {@code fits} holds: the parts asked largest give way first (each is capped at
	 * a common size, in twentieths, never under what was asked of it and 100 %, nor under {@code floor}); {@code asked} itself
	 * when it fits, or when nothing does.
	 */
	static float[] fit(float[] asked, float floor, java.util.function.Predicate<float[]> fits) {
		if (fits.test(asked)) {
			return asked.clone();
		}
		float top = 0.0F;
		for (float a : asked) {
			top = Math.max(top, a);
		}
		float lowest = floor;
		for (float a : asked) {
			lowest = Math.max(lowest, Math.min(a, 1.0F));
		}
		float[] tried = asked.clone();
		int least = (int) Math.ceil(lowest * STEPS - EPSILON);
		for (int step = (int) Math.floor(top * STEPS - EPSILON); step >= least; step--) {
			float cap = step / STEPS;
			for (int i = 0; i < asked.length; i++) {
				tried[i] = Math.min(asked[i], Math.max(cap, Math.min(asked[i], 1.0F)));
			}
			if (fits.test(tried)) {
				return tried.clone();
			}
		}
		for (int i = 0; i < asked.length; i++) {
			tried[i] = Math.min(asked[i], Math.max(lowest, Math.min(asked[i], 1.0F)));
		}
		return tried;
	}

	/** A width at a size, from its width at 100 % (fitting tries many sizes; text is measured only at the one chosen), with room for rounding. */
	private static int estimate(Measure measure, float scale) {
		return scale == 1.0F ? measure.at(1.0F) : (int) Math.ceil(measure.at(1.0F) * scale) + 2;
	}

	/** The account pill and the icons beside it, at {@code scale}. */
	private static int rightWidth(Input in, float scale, boolean estimated) {
		int pill = estimated ? estimate(in.pill, scale) : in.pill.at(scale);
		return pill + in.icons * (scaled(ICON_BUTTON, scale) + scaled(TOP_GAP, scale));
	}

	/** The stack's height to the version line's baseline. */
	private static int stackHeight(Input in, float[] s, Gaps gaps) {
		float sl = s[0];
		float sb = s[1];
		float sv = s[2];
		int n = in.buttons;
		int buttons = n * scaled(BUTTON_H, sb) + (n - 2) * scaled(gaps.button(), sb) + scaled(gaps.store(), sb);
		return scaled(MARK_HEIGHT, sl) + scaled(gaps.logo(), (sl + sb) / 2.0F) + buttons + scaled(gaps.quit(), sb) + scaled(QUIT_H, sb)
			+ footerOffset(in, sb, sv, gaps.footer());
	}

	/** The room the stack needs: its height, and what the version line hangs below its baseline once made larger. */
	private static int need(Input in, float[] s, Gaps gaps) {
		return stackHeight(in, s, gaps) + hang(in, s[2]);
	}

	/** What a version line made larger hangs below its baseline beyond what it did at 100 % (0 at 100 % and below). */
	private static int hang(Input in, float sv) {
		return sv > 1.0F ? (int) Math.ceil(in.footerDescent * (sv - 1.0F)) : 0;
	}

	/**
	 * From Quit Game's foot to the version line's baseline: the gap above its capitals (with both sizes) and the capitals
	 * themselves (with the line's own); {@code footerGap} at 100 %.
	 */
	private static int footerOffset(Input in, float sb, float sv, int footerGap) {
		float above = footerGap - in.footerCap;
		return Math.round(above * (sb + sv) / 2.0F + in.footerCap * sv);
	}

	/** The stack's widest part, estimated. */
	private static int widest(Input in, float[] s) {
		return Math.max(Math.max(estimate(in.logo, s[0]), scaled(BUTTON_W, s[1])), estimate(in.footer, s[2]));
	}
}

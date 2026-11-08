package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.feature.DailyRewards;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.store.RewardsClient;
import com.flintclient.client.control.store.StoreEndpoint;
import com.flintclient.client.control.store.StoreHandoff;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.MeritIcon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The Daily Rewards window's content ({@link DailyRewardsScreen}), a check-in in three calm rows on one plain panel, nothing
 * boxed inside boxes, with room to breathe: the header (the back button, the title with a quiet line under it, the Flint Store
 * button and the Merit balance); the week, seven days spread evenly across the panel, each its reward over its Merit (bright
 * once claimed and for today's reward, dim for the days to come; the crown on the week's last day) and "Day N" under it with a
 * tick once claimed, the Merits joined by a thin line that fills as the streak grows ("COLLECT NOW" over today's reward while
 * it waits); a hairline, then
 * the streak and the best streak at the left and one big button at the right: Claim, or once claimed the wait for the
 * next day, counting down inside it (or why the claim was refused). Without rewards to show, that row says why. One accent
 * (the theme's), quiet grey for what is secondary, Minecraft's font without a shadow.
 *
 * <p>Motion is quick and small: the days and the row rise in one after another, the balance counts up, the line fills. A claim
 * plays Flint's chime, the day's Merit pops, is ticked and shows what it gave, a few Merits fly into the balance, which counts
 * up. None of it with reduced motion. While the rewards are loading the days shimmer. When the window is short the gaps
 * tighten, then the Merits shrink, so nothing overlaps. Same page on every Minecraft line. Sizes in Dawn's design pixels
 * ({@link MenuStyle#d}).
 */
final class RewardsPage extends Node {
	private static final int DAYS = 7;
	private static final float PAD_X = 48;
	private static final float PAD_TOP = 44;
	private static final float PAD_BOTTOM = 48;
	private static final float GAP_HEAD = 72;
	private static final float GAP_ROW = 48;
	private static final float GAP_DIVIDER = 44;
	private static final float STAR = 88;
	/** The fewest of the gaps a short window keeps (a share of each) before the Merits shrink. */
	private static final float MIN_GAPS = 0.35F;
	/** The layers of Claim's hover glow (each 2/255 of the accent, so no step between them shows). */
	private static final int PILL_GLOW_LAYERS = 14;
	/** The Merit's gold, for the crown on the week's last day. */
	static final int GOLD = 0xFFE3B23C;
	/** A refusal's mark: the Flint Store's warning amber. */
	private static final int WARN = 0xFFD9BD5C;
	private static final long POP_MILLIS = 460;
	private static final long FLIGHT_MILLIS = 720;
	private static final long FLIGHT_STAGGER = 55;
	private static final int SPARKS = 6;
	private static final long ENTER_MILLIS = 420;
	private static final long ENTER_STAGGER = 45;
	/** The time between claims. */
	private static final long WAIT_MILLIS = 86_400_000L;

	private final DailyRewards rewards = DailyRewards.get();
	private final Label title = new Label(Type.HEADER_TITLE, "Daily Rewards");
	private final Label subtitle = new Label(Type.PILL_NOTE, "Come back every day: rewards grow through the week.");
	private final Label balance = new Label(Type.PILL, "0");
	/** The balance the chip always keeps room for, so the Store button beside it stays put while the balance counts up. */
	private final Label balanceRoom = new Label(Type.PILL, "0,000");
	private final Label collect = new Label(Type.PILL_NOTE, "COLLECT NOW");
	private final Label streak = new Label(Type.PILL, "");
	private final Label best = new Label(Type.PILL_NOTE, "");
	private final Label[] dayNames = new Label[DAYS];
	private final Label[] dayNumbers = new Label[DAYS];
	private final Label[] dayRewards = new Label[DAYS];
	private final Label message = new Label(Type.PILL, "");
	private final Label note = new Label(Type.PILL_NOTE, "");
	private final Label plus = new Label(Type.PILL, "");
	private final Parts.Square back;
	private final ClaimButton claim;
	private final StoreButton store;
	/** The value each label was last set from, so text is remade only when it changes. */
	private long shownBalance = Long.MIN_VALUE;
	private int shownStreak = -1;
	private int shownBest = -1;
	private int shownFirstDay = -1;
	private long shownSeconds = -1;
	// ---------- layout (set in layout) ----------
	private float line;
	private float left;
	private float right;
	private float hb;
	private float headerCY;
	private float subCY;
	private float colW;
	private float star;
	private float collectCY;
	private float amountCY;
	private float starCY;
	private float dividerY;
	private float dayCY;
	private float actionY;
	private float actionH;
	/** Whether the Store button fits in the header beside the title at all (the window's width decides). */
	private boolean storeRoom = true;
	// ---------- drawn this frame, for the claim moment ----------
	private float chipIconX;
	/** How far the page shows today's claim (1) rather than the wait for the next one (0), eased. */
	private float claimLit = -1;
	// ---------- the claim moment ----------
	private int seenClaims;
	private long momentAt = -1;
	private int momentDay;
	private final long openedAt = Clock.millis();
	/** When the rewards were first there to show, for the entrance (the days rising in one after another). */
	private long dataAt = -1;

	RewardsPage(Runnable onClose) {
		for (int i = 0; i < DAYS; i++) {
			dayNames[i] = new Label(Type.PILL_NOTE, "Day " + (i + 1));
			dayNumbers[i] = new Label(Type.PILL_NOTE, Integer.toString(i + 1));
			dayRewards[i] = new Label(Type.PILL_NOTE, "");
		}
		back = add(new Parts.Square(Glyph.CHEVRON_LEFT, "Back", onClose));
		claim = add(new ClaimButton());
		store = add(new StoreButton());
		seenClaims = rewards.claims();
		rewards.update(System.currentTimeMillis());
	}

	/** The height of one line of the menu's type, in layout units (Minecraft's 9-pixel line at the menu's pixel scale). */
	private static float lineHeight(Canvas c) {
		return MenuStyle.pixelScale() * 9F / Math.max(1, c.scale());
	}

	private static float headerHeight(float line) {
		return Math.max(d(44), line + d(18));
	}

	/** The header row and the quiet line under the title. */
	private static float headerBlock(float line) {
		return headerHeight(line) + d(10) + line;
	}

	private static float actionHeight(float line) {
		return Math.max(d(56), 2 * line + d(18));
	}

	/** A day's column without its Merit: "COLLECT NOW", the reward, "Day N" and the space between them. */
	private static float dayFixed(float line) {
		return 3 * line + d(8) + d(16) + d(18);
	}

	/** Every gap at its full size: the panel's padding, header to week, week to the hairline, hairline to the button. */
	private static float gapsTotal() {
		return d(PAD_TOP + GAP_HEAD + GAP_ROW + GAP_DIVIDER + PAD_BOTTOM);
	}

	/** The page's height with its full gaps and Merits: the window is at least this tall when it can be. */
	static float naturalHeight(Canvas m) {
		float line = lineHeight(m);
		return headerBlock(line) + dayFixed(line) + d(STAR) + d(1) + actionHeight(line) + gapsTotal();
	}

	/** A Merit's size at a whole multiple of its art (sharp), never above {@code size} by more than a little. */
	private static float crisp(Canvas m, float size) {
		int px = m.px(size);
		int sharp = MeritIcon.crispSize(px);
		if (sharp > px * 1.1F) {
			sharp = px >= 46 ? px / 23 * 23 : px;
		}
		return sharp / (float) Math.max(1, m.scale());
	}

	@Override
	protected void layout() {
		Canvas m = measure();
		line = lineHeight(m);
		left = x + d(PAD_X);
		right = Math.max(left, x + w - d(PAD_X));
		hb = headerHeight(line);
		actionH = actionHeight(line);
		colW = (right - left) / DAYS;
		star = Math.max(d(20), Math.min(d(STAR), colW - d(40)));
		// Every gap grows or shrinks in proportion to fill the window; below a third of their size the Merits shrink instead.
		float fixed = headerBlock(line) + dayFixed(line) + d(1) + actionH;
		float gaps = gapsTotal();
		float k = (h - fixed - star) / gaps;
		if (k < MIN_GAPS) {
			k = MIN_GAPS;
			star = Math.max(d(20), h - fixed - gaps * k);
		}
		star = crisp(m, star);
		k = Math.max(MIN_GAPS, (h - fixed - star) / gaps);
		float at = y + d(PAD_TOP) * k;
		headerCY = at + hb / 2;
		subCY = at + hb + d(10) + line / 2;
		at += headerBlock(line) + d(GAP_HEAD) * k;
		collectCY = at + line / 2;
		amountCY = collectCY + line + d(8);
		starCY = amountCY + line / 2 + d(16) + star / 2;
		dayCY = starCY + star / 2 + d(18) + line / 2;
		dividerY = dayCY + line / 2 + d(GAP_ROW) * k;
		actionY = dividerY + d(1) + d(GAP_DIVIDER) * k;

		back.setBounds(left, headerCY - hb / 2, hb, hb);
		float reserve = chipWidth(m, balanceRoom);
		float sw = store.preferredWidth(m);
		float sx = right - reserve - d(10) - sw;
		storeRoom = sx >= left + hb + d(16) + 2 * title.width(m) + d(20);
		store.setBounds(sx, headerCY - hb / 2, sw, hb);
		store.setVisible(storeRoom);
		float width = right - left;
		// The button: a pill sized to what it says, right-aligned and centred in the row.
		float bh = Math.min(actionH, Math.max(d(42), line + d(20)));
		float bw = Math.min(width, claim.preferredWidth(m));
		claim.setBounds(right - bw, actionY + (actionH - bh) / 2, bw, bh);
	}

	// ---------- colours (from the menu's theme, so every look keeps them) ----------

	/** Secondary text: the theme's grey, lifted a little so Minecraft's font stays easy to read. */
	private static int muted() {
		return Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, 0.3F);
	}

	/** The accent as text: a touch lighter than the accent itself. */
	private static int accentText() {
		return Palette.lerp(MenuStyle.ACCENT, 0xFFFFFFFF, 0.28F);
	}

	private static int hairline() {
		return Palette.withAlpha(MenuStyle.TEXT, 0.08F);
	}

	/**
	 * A soft glow of the accent around the box at ({@code x}, {@code y}, {@code w} × {@code h}, corners {@code radius}), reaching
	 * {@code out} beyond it: {@code layers} faint boxes, each {@code peak / layers} of the accent, grown from the box by
	 * {@code out × (1 - √(k / layers))}, so they lie sparse at the rim and dense near the box. Together they fade as the square
	 * of the way out, with no edge and no rings.
	 */
	private static void glow(Canvas c, float x, float y, float w, float h, float radius, float out, int layers, float peak) {
		int ink = Palette.withAlpha(MenuStyle.ACCENT, peak / layers);
		if ((ink >>> 24) == 0) {
			return;
		}
		for (int k = 0; k < layers; k++) {
			float g = out * (1 - (float) Math.sqrt(k / (float) layers));
			MenuStyle.box(c, x - g, y - g, w + 2 * g, h + 2 * g, radius + g, ink);
		}
	}

	/** The ink on an accent fill: dark on a light accent (Flint's orange, as the Store's buttons), light on a deep one. */
	static int onAccent() {
		int a = MenuStyle.ACCENT;
		float lum = 0.2126F * channel(a >> 16) + 0.7152F * channel(a >> 8) + 0.0722F * channel(a);
		return lum > 0.18F ? Palette.lerp(a, 0xFF000000, 0.86F) : MenuStyle.TEXT;
	}

	private static float channel(int v) {
		float c = (v & 0xFF) / 255.0F;
		return c <= 0.04045F ? c / 12.92F : (float) Math.pow((c + 0.055F) / 1.055F, 2.4);
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		long now = System.currentTimeMillis();
		rewards.update(now);
		RewardsClient.Rewards data = rewards.rewards();
		noticeClaim(data);
		if (data != null && dataAt < 0) {
			dataAt = Clock.millis();
		}
		refreshLabels(data, now);
		boolean can = data != null && rewards.claimable(now);
		boolean busy = data != null && rewards.claiming();
		claimLit = MenuStyle.ease(claimLit, can || busy);
		if (claim.visible() != (data != null)) {
			claim.setVisible(data != null);
		}

		paintHeader(c, data);
		paintWeek(c, data, can || busy);
		float in = enter(DAYS + 1);
		float before = entering(c, in);
		try {
			// A hairline between the week and the row under it.
			c.fill(c.px(left), c.px(dividerY), c.px(right), c.px(dividerY) + Math.max(1, c.px(d(1))), hairline());
			if (data == null) {
				paintWithout(c);
			} else {
				paintStats(c, data);
			}
		} finally {
			entered(c, in, before);
		}
	}

	/** The Store button rises in with the header, Claim with its row. */
	@Override
	protected void paintChildren(Canvas c) {
		paintChild(c, back);
		MenuStyle.appearing(c, store, enter(0), d(10), (canvas, node) -> paintChild(canvas, node));
		MenuStyle.appearing(c, claim, enter(DAYS + 1), d(10), (canvas, node) -> paintChild(canvas, node));
	}

	/** The title (twice the type's size) after the back button, and the balance at the right with the Store button before it. */
	private void paintHeader(Canvas c, RewardsClient.@Nullable Rewards data) {
		float chipW = data != null ? paintBalance(c) : 0;
		// A balance wider than the room kept for it (10,000 Merits and up) moves the Store button along, or hides it.
		float sx = right - Math.max(chipW, chipWidth(c, balanceRoom)) - d(10) - store.width();
		float tx = left + hb + d(16);
		boolean fits = storeRoom && sx >= tx + 2 * title.width(c) + d(20);
		if (fits && Math.abs(store.x() - sx) > 0.25F) {
			store.setBounds(sx, store.y(), store.width(), store.height());
		}
		if (store.visible() != fits) {
			store.setVisible(fits);
		}
		float room = (fits ? sx : right - chipW) - d(16) - tx;
		if (2 * title.width(c) <= room) {
			big(c, title, tx, headerCY, MenuStyle.TEXT);
		} else {
			MenuStyle.fitted(c, title, tx, headerCY, MenuStyle.TEXT, Math.max(0, room));
		}
		MenuStyle.fitted(c, subtitle, tx, subCY, muted(), Math.max(0, right - tx));
	}

	private float chipWidth(Canvas c, Label text) {
		float icon = Math.min(d(22), hb - d(12));
		return d(14) + icon + d(10) + text.width(c) + d(16);
	}

	/**
	 * The balance, as the Store's header shows it: the Merit and the number in a hairline box at the top right, counting up after a
	 * claim; the box takes the accent for a moment as the Merits arrive. Returns its width.
	 */
	private float paintBalance(Canvas c) {
		float ch = hb;
		float icon = Math.min(d(22), ch - d(12));
		float cw = Math.max(chipWidth(c, balance), chipWidth(c, balanceRoom));
		float cx = right - cw;
		float cy = headerCY - ch / 2;
		float pulse = momentPulse();
		float rad = d(10);
		MenuStyle.box(c, cx, cy, cw, ch, rad, Palette.withAlpha(MenuStyle.ACCENT, 0.10F * pulse));
		MenuStyle.ring(c, cx, cy, cw, ch, rad, Math.max(1, c.px(d(1))),
			Palette.lerp(Palette.withAlpha(MenuStyle.TEXT, 0.12F), Palette.withAlpha(MenuStyle.ACCENT, 0.7F), pulse));
		// The Merit and the number centred in the box (it keeps room for four digits).
		float content = icon + d(10) + balance.width(c);
		float at = cx + (cw - content) / 2;
		chipIconX = at + icon / 2;
		if (pulse > 0.001F) {
			MeritIcon.drawMoving(c, c.px(chipIconX), c.px(headerCY), c.px(icon * (1 + 0.15F * pulse)), 0xFFFFFFFF);
		} else {
			MeritIcon.draw(c, c.px(chipIconX), c.px(headerCY), c.px(icon), 0xFFFFFFFF);
		}
		MenuStyle.text(c, balance, at + icon + d(10), headerCY, MenuStyle.TEXT);
		return cw;
	}

	/** The balance shown: counting up from nothing on opening, and from the old total after a claim (as every view does). */
	private long shownValue(RewardsClient.@Nullable Rewards data, long now) {
		if (data == null) {
			return -2;
		}
		long value = rewards.shownBalance(now);
		float k = momentAt >= 0 || dataAt < 0 || Kit.reduced() ? 1 : MenuStyle.appear(dataAt + 150, 900);
		return Math.round(value * (double) k);
	}

	/** The week: the line joining the days, then each day rising in after the one before. */
	private void paintWeek(Canvas c, RewardsClient.@Nullable Rewards data, boolean open) {
		List<RewardsClient.Day> track = data == null ? List.of() : data.track();
		int count = data == null ? DAYS : Math.min(DAYS, track.size());
		int claimed = 0;
		if (data != null) {
			for (int i = 0; i < count; i++) {
				if (data.streak() > 0 && track.get(i).day() <= data.streak()) {
					claimed++;
				}
			}
		}
		float in = enter(1);
		float before = entering(c, in);
		try {
			paintLinks(c, data == null ? 0 : progress(claimed), count);
		} finally {
			entered(c, in, before);
		}
		boolean loading = data == null && rewards.state() == DailyRewards.State.LOADING;
		for (int i = 0; i < count; i++) {
			in = enter(1 + i);
			before = entering(c, in);
			try {
				if (data == null) {
					paintGhost(c, i, loading);
				} else {
					paintDay(c, i, track.get(i), data, open);
				}
			} finally {
				entered(c, in, before);
			}
		}
	}

	/** How many days the line shows claimed (fractional while it fills): on opening from none, after a claim from the day before. */
	private float progress(int claimed) {
		if (claimed <= 0 || Kit.reduced()) {
			return claimed;
		}
		if (momentAt >= 0 && momentDay - Math.max(1, shownFirstDay) + 1 == claimed) {
			return claimed - 1 + MenuStyle.appear(momentAt + 120, 560);
		}
		return claimed * (dataAt < 0 ? 0 : MenuStyle.appear(dataAt + 260, 720));
	}

	/** How full the stretch of line from day {@code link + 1} to the next is, with {@code progress} days claimed. */
	static float linkFill(float progress, int link) {
		return Math.clamp(progress - (link + 1), 0, 1);
	}

	/** A thin line between each two days' Merits, at their middle: faint, filling with the accent as the streak reaches each day. */
	private void paintLinks(Canvas c, float progress, int count) {
		float th = d(3);
		for (int i = 0; i + 1 < count; i++) {
			float x0 = left + colW * (i + 0.5F) + star / 2 + d(16);
			float x1 = left + colW * (i + 1.5F) - star / 2 - d(16);
			if (x1 - x0 < d(8)) {
				return;
			}
			float ty = starCY - th / 2;
			MenuStyle.box(c, x0, ty, x1 - x0, th, th / 2, Palette.withAlpha(MenuStyle.TEXT, 0.08F));
			float fill = linkFill(progress, i);
			if (fill > 0.005F) {
				MenuStyle.box(c, x0, ty, Math.max(th, (x1 - x0) * fill), th, th / 2, MenuStyle.ACCENT);
			}
		}
	}

	/**
	 * One day, in a column: its reward over its Merit (bright once claimed and for today's reward, dimmer for the next day, dim
	 * after), with the crown on the week's last day, and "Day N" under it with a tick once claimed. Today's, while its reward
	 * waits, has "COLLECT NOW" over its reward and its reward and name in the accent.
	 */
	private void paintDay(Canvas c, int i, RewardsClient.Day day, RewardsClient.Rewards data, boolean open) {
		float cx = left + colW * (i + 0.5F);
		boolean claimedDay = data.streak() > 0 && day.day() <= data.streak();
		boolean current = !claimedDay && day.day() == data.nextDay();
		boolean ready = current && open;
		float lit = Math.clamp(claimLit, 0, 1);
		// The glow and the words over today's reward fade out from the day just claimed, not from the next one.
		int litDay = momentAt >= 0 ? momentDay : data.nextDay();
		float shine = day.day() == litDay ? lit : 0;
		long millis = Clock.millis();
		if (shine > 0.01F) {
			float before = c.opacity();
			c.setOpacity(before * shine);
			float cw = collect.width(c);
			float lx = Math.clamp(cx, left - d(PAD_X) / 2 + cw / 2, right + d(PAD_X) / 2 - cw / 2);
			MenuStyle.centered(c, collect, lx, collectCY, accentText(), cw + d(4));
			c.setOpacity(before);
		}
		paintAmount(c, i, cx, day.day() % DAYS == 0, claimedDay, ready ? lit : 0);
		float pop = 0;
		if (momentAt >= 0 && day.day() == momentDay && !Kit.reduced()) {
			float t = Math.clamp((millis - momentAt) / (float) POP_MILLIS, 0, 1);
			pop = (float) Math.sin(Math.PI * t) * (1 - t * 0.35F);
		}
		boolean zoomed = pop > 0.001F;
		if (zoomed) {
			c.pushZoom(1 + 0.08F * pop, c.px(cx), c.px(starCY));
		}
		try {
			float bob = ready && !Kit.reduced() ? (float) Math.sin((millis - openedAt) / 640.0) * d(2.5F) * lit : 0;
			int tint = claimedDay || ready ? 0xFFFFFFFF : current ? 0x99FFFFFF : 0x4DFFFFFF;
			MeritIcon.draw(c, c.px(cx), c.px(starCY + bob), MeritIcon.crispSize(c.px(star)), tint);
		} finally {
			if (zoomed) {
				c.popOffset();
			}
		}
		float tick = 0;
		if (claimedDay) {
			tick = momentAt >= 0 && day.day() == momentDay && !Kit.reduced()
				? Math.clamp((millis - momentAt - POP_MILLIS * 0.35F) / 220.0F, 0, 1) : 1;
		}
		paintDayName(c, i, day, cx, ready ? lit : 0, claimedDay, tick);
	}

	/** The day's reward, centred over its Merit: the crown before it on the week's last day. */
	private void paintAmount(Canvas c, int i, float cx, boolean crown, boolean claimedDay, float today) {
		Label reward = dayRewards[i];
		float s = line * 0.8F;
		float total = reward.width(c) + (crown ? s + d(6) : 0);
		float at = cx - total / 2;
		if (crown) {
			Glyph.CROWN.draw(c, at + s / 2, amountCY, s, Palette.withAlpha(GOLD, claimedDay || today > 0.5F ? 1 : 0.7F));
			at += s + d(6);
		}
		int ink = claimedDay ? MenuStyle.TEXT : Palette.lerp(muted(), accentText(), today);
		MenuStyle.text(c, reward, at, amountCY, ink);
	}

	/**
	 * "Day N" under the Merit ("N" in a narrow column), after a tick in the accent once claimed ({@code tick}: how far it is
	 * in); just claimed, it gives way to what the day gave for a moment.
	 */
	private void paintDayName(Canvas c, int i, RewardsClient.Day day, float cx, float today, boolean claimedDay, float tick) {
		Label name = dayNames[i].width(c) <= colW - d(8) ? dayNames[i] : dayNumbers[i];
		float swap = 0;
		if (momentAt >= 0 && day.day() == momentDay && !Kit.reduced()) {
			long since = Clock.millis() - momentAt;
			float in = MenuStyle.appear(momentAt + 80, 260);
			float out = since < 1300 ? 0 : MenuStyle.appear(momentAt + 1300, 300);
			swap = in * (1 - out);
		}
		int ink = claimedDay ? Palette.lerp(muted(), MenuStyle.TEXT, 0.45F) : Palette.lerp(Palette.withAlpha(muted(), 0.8F), accentText(), today);
		float before = c.opacity();
		if (swap < 0.99F) {
			c.setOpacity(before * (1 - swap));
			float s = line * 0.8F;
			boolean mark = claimedDay && s + d(6) + name.width(c) <= colW - d(8);
			float total = (mark ? s + d(6) : 0) + name.width(c);
			float at = cx - total / 2;
			if (mark) {
				Glyph.CHECK.draw(c, at + s / 2, dayCY - swap * d(8), s, Palette.withAlpha(MenuStyle.ACCENT, tick));
				at += s + d(6);
			}
			MenuStyle.fitted(c, name, at, dayCY - swap * d(8), ink, colW - d(4));
			c.setOpacity(before);
		}
		if (swap > 0.01F) {
			c.setOpacity(before * swap);
			MenuStyle.centered(c, plus, cx, dayCY + (1 - swap) * d(8), accentText(), colW - d(4));
			c.setOpacity(before);
		}
	}

	/** A day while there are none to show: its Merit faint and its name, shimmering while the rewards load. */
	private void paintGhost(Canvas c, int i, boolean loading) {
		float cx = left + colW * (i + 0.5F);
		float wave = Kit.reduced() || !loading ? 0 : (float) Math.sin((Clock.millis() - openedAt) / 300.0 - i * 0.5);
		float alpha = loading ? 0.28F + 0.12F * wave : 0.22F;
		MeritIcon.draw(c, c.px(cx), c.px(starCY), MeritIcon.crispSize(c.px(star)), Palette.withAlpha(0xFFFFFFFF, alpha));
		MenuStyle.centered(c, dayNames[i], cx, dayCY, Palette.withAlpha(muted(), 0.6F), colW - d(4));
	}

	/** The streak (its flame in the accent once there is one) and the best streak under it, left of the big button. */
	private void paintStats(Canvas c, RewardsClient.Rewards data) {
		float maxW = claim.x() - d(24) - left;
		if (maxW < d(60)) {
			return;
		}
		float s = line * 0.9F;
		float cy = actionY + actionH / 2;
		float topY = cy - line / 2 - d(7);
		float lowY = cy + line / 2 + d(7);
		Glyph.FIRE.draw(c, left + s / 2, topY, s, data.streak() > 0 ? MenuStyle.ACCENT : MenuStyle.NEUTRAL);
		MenuStyle.fitted(c, streak, left + s + d(10), topY, MenuStyle.TEXT, maxW - s - d(10));
		Glyph.TROPHY.draw(c, left + s / 2, lowY, s * 0.9F, MenuStyle.NEUTRAL);
		MenuStyle.fitted(c, best, left + s + d(10), lowY, muted(), maxW - s - d(10));
	}

	/** Why there are no rewards to show, where the button would be: what is happening over what to do. */
	private void paintWithout(Canvas c) {
		DailyRewards.State state = rewards.state();
		switch (state) {
			case LOADING -> say("Getting your rewards…", "One moment while Flint checks your account.");
			case NO_ACCOUNT -> say("Rewards need a Microsoft account", "Sign in with Microsoft in the Flint app, then play with it.");
			case OFFLINE -> say("Can't reach Flint right now", "Check your connection. Flint tries again by itself.");
			case PAUSED -> {
				String why = rewards.closedReason();
				if ("locked".equals(why)) {
					say("Rewards are on hold for this account", "Contact Flint support if you think this is a mistake.");
				} else if ("account_too_new".equals(why)) {
					say("Rewards open a little later for new accounts", "Keep playing and check back in a few days.");
				} else {
					say("Daily rewards are paused", "This page updates by itself when they're back.");
				}
			}
			case STORE_ACCOUNT -> say("Sign in to the Flint Store first", "Sign in once on the Store page in the Flint app.");
			default -> say("Daily rewards aren't in this build", "Update Flint to collect Merits every day.");
		}
		float rad = d(12);
		float bw = right - left;
		MenuStyle.box(c, left, actionY, bw, actionH, rad, Palette.withAlpha(MenuStyle.TEXT, 0.04F));
		MenuStyle.ring(c, left, actionY, bw, actionH, rad, Math.max(1, c.px(d(1))), hairline());
		float s = line * 1.1F;
		float cy = actionY + actionH / 2;
		float gx = left + d(22) + s / 2;
		boolean loading = state == DailyRewards.State.LOADING;
		(loading ? Glyph.HOURGLASS : Glyph.INFO).draw(c, gx, cy, s, loading ? MenuStyle.NEUTRAL : WARN);
		float tx = gx + s / 2 + d(16);
		float maxW = Math.max(0, right - d(22) - tx);
		MenuStyle.fitted(c, message, tx, cy - line / 2 - d(4), MenuStyle.TEXT, maxW);
		MenuStyle.fitted(c, note, tx, cy + line / 2 + d(4), muted(), maxW);
	}

	private void say(String what, String why) {
		if (!message.text().equals(what)) {
			message.set(what);
		}
		if (!note.text().equals(why)) {
			note.set(why);
		}
	}

	/** What a refused claim means for the player, by the server's code. */
	static String refusalText(String code) {
		return switch (code) {
			case "ip_daily_limit" -> "This network already collected today's rewards. Come back after the daily reset.";
			case "daily_cap_reached" -> "Today's rewards are all handed out. Come back after the daily reset.";
			case "disabled" -> "Daily rewards were just switched off. This page updates by itself.";
			case "store_account_required" -> "Sign in to the Flint Store once with this account first.";
			default -> "Flint couldn't give this reward right now. Try again a little later.";
		};
	}

	/** {@link #refusalText} in a few words, for the button. */
	static String refusalShort(String code) {
		return switch (code) {
			case "ip_daily_limit" -> "Already collected on this network today.";
			case "daily_cap_reached" -> "Today's rewards are all handed out.";
			case "disabled" -> "Daily rewards were just switched off.";
			case "store_account_required" -> "Sign in to the Flint Store first.";
			default -> "Flint couldn't give this reward right now.";
		};
	}

	/** The streak as the page says it. */
	static String streakText(int days) {
		return days <= 0 ? "No streak yet" : "Streak: " + days + (days == 1 ? " day" : " days");
	}

	/** The best streak as the page says it. */
	static String bestText(int days) {
		return "Best: " + Math.max(0, days) + (days == 1 ? " day" : " days");
	}

	/** The wait for the next reward, as hours, minutes and seconds ("19:57:05"). */
	static String countdown(long millis) {
		long seconds = Math.max(0, millis) / 1000;
		return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
	}

	/** How far the 24 hours between claims have come (0 just after a claim, 1 at the unlock), from the time still to wait. */
	static float waitProgress(long untilMillis) {
		return Math.clamp(1 - untilMillis / (float) WAIT_MILLIS, 0, 1);
	}

	/** Remakes the texts whose numbers changed. */
	private void refreshLabels(RewardsClient.@Nullable Rewards data, long now) {
		long value = shownValue(data, now);
		if (value != shownBalance) {
			shownBalance = value;
			balance.set(data == null ? "—" : String.format(Locale.ROOT, "%,d", value));
		}
		if (data == null) {
			return;
		}
		List<RewardsClient.Day> track = data.track();
		int first = track.isEmpty() ? 1 : track.get(0).day();
		if (first != shownFirstDay) {
			shownFirstDay = first;
			for (int i = 0; i < DAYS; i++) {
				RewardsClient.Day day = i < track.size() ? track.get(i) : null;
				dayNames[i].set(day == null ? "" : "Day " + day.day());
				dayNumbers[i].set(day == null ? "" : Integer.toString(day.day()));
				dayRewards[i].set(day == null ? "" : "+" + day.reward());
			}
		}
		if (data.streak() != shownStreak) {
			shownStreak = data.streak();
			streak.set(streakText(data.streak()));
		}
		if (data.best() != shownBest) {
			shownBest = data.best();
			best.set(bestText(data.best()));
		}
		long seconds = rewards.untilNext(now) / 1000;
		if (seconds != shownSeconds) {
			shownSeconds = seconds;
			claim.time.set(countdown(seconds * 1000));
		}
	}

	/** {@code label} twice its size (the menu's type is one pixel size throughout; Minecraft's font stays sharp at twice it). */
	private static void big(Canvas c, Label label, float x, float centerY, int argb) {
		c.pushZoom(2, c.px(x), c.px(centerY));
		try {
			MenuStyle.text(c, label, x, centerY, argb);
		} finally {
			c.popOffset();
		}
	}

	/**
	 * How far entrance {@code step} has come (0 to 1): the header with the window, then the days one after another, then the
	 * row under them. The days come in with the rewards (at once when they are already there, as they usually are: the main
	 * menu's card reads them first).
	 */
	private float enter(int step) {
		if (Kit.reduced()) {
			return 1;
		}
		boolean days = step > 0 && step <= DAYS;
		long base = days && dataAt >= 0 ? Math.max(dataAt, openedAt) : openedAt;
		return MenuStyle.appear(base + step * ENTER_STAGGER, ENTER_MILLIS);
	}

	/** Begins drawing something entering: faded and lowered by how far it has to come. Returns the opacity to restore. */
	private static float entering(Canvas c, float in) {
		float before = c.opacity();
		if (in < 1) {
			c.pushOffset(0, Math.round((1 - in) * d(10) * c.scale()));
			c.setOpacity(before * in);
		}
		return before;
	}

	private static void entered(Canvas c, float in, float before) {
		if (in < 1) {
			c.setOpacity(before);
			c.popOffset();
		}
	}

	// ---------- the claim moment ----------

	/** A claim that just succeeded starts the moment once: the chime, the day, the Merits flying, the count. */
	private void noticeClaim(RewardsClient.@Nullable Rewards data) {
		int claims = rewards.claims();
		if (claims == seenClaims) {
			return;
		}
		seenClaims = claims;
		if (data == null) {
			return;
		}
		momentAt = Clock.millis();
		momentDay = rewards.claimedDay();
		plus.set("+" + rewards.claimedReward());
		try {
			MenuHost.playClaimSound();
		} catch (RuntimeException | LinkageError ignored) {
			// No sound on this game: the moment is still shown.
		}
	}

	/** How lit the balance is as the Merits arrive (0 to 1 and back). */
	private float momentPulse() {
		if (momentAt < 0) {
			return 0;
		}
		float t = (Clock.millis() - momentAt - FLIGHT_MILLIS * 0.6F) / (float) (DailyRewards.COUNT_MILLIS + 300);
		return t <= 0 || t >= 1 ? 0 : (float) Math.sin(Math.PI * t);
	}

	/** A few Merits flying from the button into the balance, over everything (the moment ends with the count). */
	@Override
	protected void paintOver(Canvas c) {
		if (momentAt < 0) {
			return;
		}
		long since = Clock.millis() - momentAt;
		if (since > FLIGHT_MILLIS + SPARKS * FLIGHT_STAGGER + DailyRewards.COUNT_MILLIS + 400) {
			momentAt = -1;
			return;
		}
		if (Kit.reduced()) {
			return;
		}
		float sx = claim.x() + claim.width() / 2;
		float sy = claim.y() + claim.height() / 2;
		float ex = chipIconX;
		float ey = headerCY;
		for (int i = 0; i < SPARKS; i++) {
			float t = (since - i * FLIGHT_STAGGER) / (float) FLIGHT_MILLIS;
			if (t <= 0 || t >= 1) {
				continue;
			}
			float e = t < 0.5F ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
			// Each Merit leaves the button on its own gentle curve, then settles into the balance.
			float spread = d(40 + 22 * (i % 3)) * (i % 2 == 0 ? 1 : -1);
			float cx1 = (sx + ex) / 2 + spread;
			float cy1 = Math.min(sy, ey) - d(30 + 10 * (i % 2));
			float u = 1 - e;
			float px = u * u * sx + 2 * u * e * cx1 + e * e * ex;
			float py = u * u * sy + 2 * u * e * cy1 + e * e * ey;
			float size = d(18) * (1 - 0.35F * e);
			float alpha = Math.min(1, t * 6) * (t > 0.85F ? (1 - t) / 0.15F : 1);
			MeritIcon.drawMoving(c, c.px(px), c.px(py), Math.max(2, c.px(size)), Palette.withAlpha(0xFFFFFFFF, alpha));
		}
	}

	// ---------- the buttons ----------

	/**
	 * Claim, the page's one accent button, a pill sized to what it says (as the Store's primary buttons: the accent, dark ink):
	 * smoothly lighter under the pointer with a soft glow of the accent around it, darker and a pixel lower while held. While
	 * the claim travels it says so; once claimed it is a quiet pill of the same size with the wait for the next reward counting
	 * down in it (filling faintly as the day passes), or, wider, why the claim was refused. The two cross-fade.
	 */
	private final class ClaimButton extends Pressable {
		private final Label ready = new Label(Type.BUTTON, "Claim Daily Reward");
		private final Label working = new Label(Type.BUTTON, "Claiming…");
		private final Label nextIn = new Label(Type.BUTTON, "Next reward in");
		final Label time = new Label(Type.BUTTON, "00:00:00");
		private final Label refusedLong = new Label(Type.BUTTON, "");
		private final Label refusedShort = new Label(Type.BUTTON, "");
		private @Nullable String shownRefusal;

		ClaimButton() {
			super(null);
			setAction(() -> rewards.claim(System.currentTimeMillis()));
			name("Claim daily reward");
		}

		/** Wide enough for its labels with room around them, so it never changes size. */
		float preferredWidth(Canvas m) {
			float s = lineHeight(m) * 0.8F + d(10);
			float widest = Math.max(Math.max(ready.width(m), working.width(m)), s + nextIn.width(m) + d(8) + time.width(m));
			return Math.max(d(220), widest + 2 * d(32));
		}

		private boolean open() {
			return rewards.claimable(System.currentTimeMillis()) && !rewards.claiming();
		}

		@Override
		public boolean interactive() {
			return visible() && open();
		}

		@Override
		public boolean focusable() {
			return interactive();
		}

		@Override
		public String narration() {
			RewardsClient.Rewards data = rewards.rewards();
			return open() && data != null ? "Claim " + data.nextReward() + " Merits" : "Daily reward claimed";
		}

		@Override
		protected void paint(Canvas c) {
			if (rewards.rewards() == null) {
				return;
			}
			long now = System.currentTimeMillis();
			boolean busy = rewards.claiming();
			float lit = Math.clamp(claimLit, 0, 1);
			float rad = h / 2;
			int drop = Math.round(press * Math.max(1, c.px(d(1))));
			c.pushOffset(0, drop);
			try {
				float before = c.opacity();
				if (lit > 0.01F) {
					c.setOpacity(before * lit);
					float halo = busy ? 0 : hover * (1 - 0.5F * press);
					if (halo > 0.01F) {
						// A soft glow of the accent around the pill, fading into the panel.
						glow(c, x, y, w, h, rad, d(12), PILL_GLOW_LAYERS, 0.12F * halo);
					}
					int fill = Palette.lerp(MenuStyle.ACCENT, MenuStyle.ACCENT_HOVER, busy ? 0 : hover);
					fill = Palette.lerp(fill, MenuStyle.ACCENT_PRESS, press);
					MenuStyle.box(c, x, y, w, h, rad, busy ? Palette.withAlpha(fill, 0.75F) : fill);
					MenuStyle.centered(c, busy ? working : ready, x + w / 2, y + h / 2, onAccent(), w - d(16));
					c.setOpacity(before);
				}
				if (lit < 0.99F) {
					c.setOpacity(before * (1 - lit));
					String code = rewards.refusal(now);
					if (code != null) {
						paintRefused(c, code, rad);
					} else {
						MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.04F));
						// The day passing, as a faint fill from the left.
						float k = waitProgress(rewards.untilNext(now));
						if (k * w > h) {
							MenuStyle.box(c, x, y, w * k, h, rad, Palette.withAlpha(MenuStyle.ACCENT, 0.07F));
						}
						MenuStyle.ring(c, x, y, w, h, rad, Math.max(1, c.px(d(1))), hairline());
						paintWait(c);
					}
					c.setOpacity(before);
				}
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			} finally {
				c.popOffset();
			}
		}

		/** "Next reward in 19:57:05" with a clock, or just the clock and the time when that doesn't fit. */
		private void paintWait(Canvas c) {
			float s = line * 0.8F;
			float cy = y + h / 2;
			boolean words = s + d(10) + nextIn.width(c) + d(8) + time.width(c) <= w - d(24);
			float total = s + d(10) + (words ? nextIn.width(c) + d(8) : 0) + time.width(c);
			float at = x + (w - total) / 2;
			Glyph.CLOCK.draw(c, at + s / 2, cy, s, muted());
			at += s + d(10);
			if (words) {
				MenuStyle.text(c, nextIn, at, cy, muted());
				at += nextIn.width(c) + d(8);
			}
			MenuStyle.text(c, time, at, cy, MenuStyle.TEXT);
		}

		/**
		 * Why the claim was refused, with the warning mark, in a quiet pill that grows to the left to fit it (up to most of the
		 * row; the short words when the long ones don't fit).
		 */
		private void paintRefused(Canvas c, String code, float rad) {
			if (!code.equals(shownRefusal)) {
				shownRefusal = code;
				refusedLong.set(refusalText(code));
				refusedShort.set(refusalShort(code));
			}
			float s = line * 0.85F;
			float pad = d(24);
			float most = Math.max(w, (right - left) * 0.62F);
			float roomMost = most - 2 * pad - s - d(10);
			Label why = refusedLong.width(c) <= roomMost ? refusedLong : refusedShort;
			float pw = Math.min(most, Math.max(w, 2 * pad + s + d(10) + why.width(c)));
			float px = x + w - pw;
			MenuStyle.box(c, px, y, pw, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.04F));
			MenuStyle.ring(c, px, y, pw, h, rad, Math.max(1, c.px(d(1))), hairline());
			float room = pw - 2 * pad - s - d(10);
			float total = s + d(10) + Math.min(room, why.width(c));
			float at = px + (pw - total) / 2;
			float cy = y + h / 2;
			Glyph.INFO.draw(c, at + s / 2, cy, s, WARN);
			MenuStyle.fitted(c, why, at + s + d(10), cy, muted(), room);
		}
	}

	/**
	 * Flint Store: a quiet button as the Store's own (the text at 6% with a hairline, lighter under the pointer, its arrow nudging
	 * right); it opens the Store's cosmetics in the browser after Minecraft's link confirmation, which comes back here.
	 */
	private final class StoreButton extends Pressable {
		private final Label label = new Label(Type.BUTTON, "Flint Store");

		StoreButton() {
			super(null);
			setAction(this::open);
			name("Flint Store");
			tooltip("Opens the Flint Store in your browser");
		}

		float preferredWidth(Canvas m) {
			return label.width(m) + d(10) + lineHeight(m) * 0.7F + 2 * d(16);
		}

		private void open() {
			KitScreen screen = screen();
			if (screen == null) {
				return;
			}
			String url = storeUrl();
			try {
				MenuHost.openLink(screen, url);
			} catch (RuntimeException | LinkageError e) {
				com.flintclient.client.FlintLog.LOGGER.warn("Flint: couldn't open {}", url, e);
			}
		}

		@Override
		protected void paint(Canvas c) {
			float rad = d(10);
			int drop = Math.round(press * Math.max(1, c.px(d(1.5F))));
			c.pushOffset(0, drop);
			try {
				MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.04F + 0.05F * hover - 0.02F * press));
				MenuStyle.ring(c, x, y, w, h, rad, Math.max(1, c.px(d(1))), Palette.withAlpha(MenuStyle.TEXT, 0.10F + 0.08F * hover));
				float s = line * 0.7F;
				float total = label.width(c) + d(10) + s;
				float lx = x + (w - total) / 2;
				MenuStyle.text(c, label, lx, y + h / 2, Palette.lerp(muted(), MenuStyle.TEXT, 0.6F + 0.4F * hover));
				Glyph.CHEVRON_RIGHT.draw(c, lx + total - s / 2 + d(3) * hover, y + h / 2, s, Palette.lerp(muted(), MenuStyle.TEXT, hover));
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			} finally {
				c.popOffset();
			}
		}
	}

	/** The Flint Store's cosmetics, at the address the Flint app handed over (else Flint's own Store). */
	private static String storeUrl() {
		String base = StoreEndpoint.DEFAULT_BASE;
		try {
			StoreHandoff handoff = new StoreHandoff(FabricLoader.getInstance().getGameDir());
			handoff.poll();
			base = handoff.endpoint().origin();
		} catch (RuntimeException | LinkageError e) {
			// The built-in Store, as the game's capes use without a handoff.
		}
		return base + "/cosmetics/";
	}
}

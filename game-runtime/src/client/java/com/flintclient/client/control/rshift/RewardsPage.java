package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.feature.DailyRewards;
import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.control.store.RewardsClient;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.MeritIcon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.client.ui.kit.Pressable;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Daily Rewards window's content ({@link DailyRewardsScreen}): the daily streak and the Merit balance
 * ({@link DailyRewards}). Across the top, DAILY REWARDS with the streak, the best streak, the balance and the Claim button
 * (a countdown once today's reward is claimed); under it the week, a card per day (a tick on the days claimed, TODAY on
 * today's, a crown on the week's last day, the reward in its corner, the Merit in the middle, the day at the bottom) over a
 * row of segments that fill as the streak grows; then what Merits will buy. A claim is a moment: Flint's chime, the day's
 * card springs and is ticked, its "+15" rises, a spray of Merits flies from the button into the balance, which counts up.
 * Opening, the cards rise in one after another and the segments fill; today's card glows and Merits twinkle at its corners
 * (no motion with reduced motion). While the rewards are loading the cards shimmer; without them the page says why. Same
 * page on every Minecraft line. Sizes in Dawn's design pixels ({@link MenuStyle#d}).
 */
final class RewardsPage extends Node {
	private static final float PAD = 34;
	private static final float HEAD = 64;
	private static final int DAYS = 7;
	/** The Merit's own colours, from its picture: the light face, the gold, the deep edge, and ink on gold. */
	static final int GOLD_LIGHT = 0xFFFCE7A1;
	static final int GOLD = 0xFFE3B23C;
	static final int GOLD_DEEP = 0xFFB27A1C;
	static final int GOLD_INK = 0xFF2B1B04;
	private static final long COUNT_MILLIS = DailyRewards.COUNT_MILLIS;
	private static final long POP_MILLIS = 520;
	private static final long FLIGHT_MILLIS = 820;
	private static final long FLIGHT_STAGGER = 34;
	private static final int SPARKS = 12;

	private final DailyRewards rewards = DailyRewards.get();
	private final Label title = new Label(Type.HEADER_TITLE, "DAILY REWARDS");
	private final Label streakValue = new Label(Type.PILL, "0");
	private final Label streakCaption = new Label(Type.PILL_NOTE, "Streak");
	private final Label bestValue = new Label(Type.PILL, "0");
	private final Label bestCaption = new Label(Type.PILL_NOTE, "Best");
	private final Label balance = new Label(Type.PILL, "0");
	private final Label[] dayNames = new Label[DAYS];
	private final Label[] dayRewards = new Label[DAYS];
	private final Label today = new Label(Type.BADGE, "TODAY");
	private final Label spendTitle = new Label(Type.SECTION_TITLE, "SPEND YOUR MERITS");
	private final Label soon = new Label(Type.CAPTION, "FLINT STORE");
	private final Label bannerTitle = new Label(Type.NAME, "Capes, wings and more");
	private final Label bannerNote = new Label(Type.EMPTY_NOTE, "Spend your Merits on Flint cosmetics in the Store page of the Flint app.");
	/** The streak rule (with the server's largest reward), or why the last claim was refused while that holds. */
	private final Label bannerRule = new Label(Type.CAPTION, "Come back every day: rewards grow through the week.");
	private final Label message = new Label(Type.EMPTY, "");
	private final Label messageNote = new Label(Type.EMPTY_NOTE, "");
	private final Label plus = new Label(Type.PILL, "");
	private final ClaimButton claim;
	/** The value each label was last set from, so text is remade only when it changes. */
	private long shownBalance = Long.MIN_VALUE;
	private int shownStreak = -1;
	private int shownBest = -1;
	private int shownFirstDay = -1;
	// ---------- layout (set in layout and placeHeader) ----------
	private float left;
	private float right;
	private float headY;
	private float cardsY;
	private float cardW;
	private float cardH;
	private float gap;
	private float segY;
	private float spendY;
	private float bannerY;
	private float bannerH;
	private float balanceX;
	private float balanceW;
	private float bestX;
	private float bestW;
	private float streakX;
	private float streakW;
	// ---------- the claim moment ----------
	private int seenClaims;
	private long momentAt = -1;
	private int momentDay;
	private final long openedAt = Clock.millis();
	/** When the rewards were first there to show, for the entrance (the cards rising in one after another). */
	private long dataAt = -1;

	RewardsPage() {
		for (int i = 0; i < DAYS; i++) {
			dayNames[i] = new Label(Type.PILL, "");
			dayRewards[i] = new Label(Type.PILL_NOTE, "");
		}
		claim = add(new ClaimButton());
		seenClaims = rewards.claims();
		rewards.update(System.currentTimeMillis());
	}

	@Override
	protected void layout() {
		float pad = d(PAD);
		left = x + pad;
		right = x + w - pad;
		headY = y + pad;
		gap = d(18);
		cardW = (right - left - (DAYS - 1) * gap) / DAYS;
		// Top to bottom: the header (64), 30, the cards, 16, the segments (8), 44, SPEND YOUR MERITS (24), 16, the banner, the pad.
		float room = y + h - pad - (headY + d(HEAD + 30 + 16 + 8 + 44 + 24 + 16));
		cardH = Math.clamp(room * 0.56F, d(150), Math.min(d(228), cardW * 1.08F));
		cardsY = headY + d(HEAD + 30);
		segY = cardsY + cardH + d(16);
		spendY = segY + d(8 + 44);
		bannerY = spendY + d(24 + 16);
		bannerH = Math.clamp(y + h - pad - bannerY, d(96), d(240));
		float cw = d(270);
		claim.setBounds(right - cw, headY, cw, d(HEAD));
	}

	/** The header's pills, right to left from the Claim button: the balance, the best streak, the streak (each sized to its text). */
	private void placeHeader(Canvas c) {
		float g = d(10);
		balanceW = d(18 + 28 + 12) + Math.max(d(48), balance.width(c)) + d(20);
		balanceX = claim.x() - g - balanceW;
		bestW = d(18 + 22 + 10) + 2 * bestValue.width(c) + d(10) + bestCaption.width(c) + d(18);
		bestX = balanceX - g - bestW;
		streakW = d(18) + 2 * streakValue.width(c) + d(10) + streakCaption.width(c) + d(18);
		streakX = bestX - g - streakW;
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
		refreshLabels(data);
		placeHeader(c);
		float cy = headY + d(HEAD) / 2;
		big(c, title, left, cy, MenuStyle.WORDMARK);
		float titleRight = left + 2 * title.width(c) + d(12);
		if (data == null) {
			claim.setVisible(false);
			paintWithout(c);
			return;
		}
		claim.setVisible(true);
		if (streakX > titleRight) {
			pill(c, streakX, streakW, cy, null, streakValue, streakCaption, MenuStyle.ACCENT);
		}
		if (bestX > titleRight) {
			pill(c, bestX, bestW, cy, Glyph.TROPHY, bestValue, bestCaption, GOLD);
		}
		paintBalance(c, cy);

		List<RewardsClient.Day> track = data.track();
		boolean can = rewards.claimable(now);
		int todayDay = can ? data.nextDay() : Math.max(1, data.nextDay() - 1);
		for (int i = 0; i < DAYS && i < track.size(); i++) {
			float in = enter(i);
			float before = entering(c, in);
			try {
				paintDay(c, i, track.get(i), data, todayDay, can);
				paintSegment(c, i, track.get(i), data, can);
			} finally {
				entered(c, in, before);
			}
		}
		float in = enter(DAYS);
		float before = entering(c, in);
		try {
			paintSpend(c);
		} finally {
			entered(c, in, before);
		}
	}

	/** A header pill: an optional glyph, the number twice its size, and its caption. */
	private void pill(Canvas c, float px, float pw, float cy, @Nullable Glyph glyph, Label value, Label caption, int valueInk) {
		float ph = d(HEAD);
		MenuStyle.block(c, px, cy - ph / 2, pw, ph, MenuStyle.radius(), 0, 0);
		float at = px + d(20);
		if (glyph != null) {
			glyph.draw(c, at + d(11), cy, d(22), valueInk);
			at += d(22 + 10);
		}
		big(c, value, at, cy, valueInk);
		MenuStyle.text(c, caption, at + 2 * value.width(c) + d(10), cy + d(2), MenuStyle.NEUTRAL);
	}

	/** The balance pill: the Merit and the balance (counting up after a claim and on opening), lit as the Merits arrive. */
	private void paintBalance(Canvas c, float cy) {
		float ph = d(HEAD);
		float pulse = momentPulse();
		MenuStyle.block(c, balanceX, cy - ph / 2, balanceW, ph, MenuStyle.radius(), 0.25F * pulse, 0);
		MenuStyle.ring(c, balanceX, cy - ph / 2, balanceW, ph, MenuStyle.radius(), Math.max(1, c.px(d(1.25F))),
			Palette.withAlpha(GOLD, 0.30F + 0.5F * pulse));
		float iconX = balanceX + d(18) + d(14);
		if (pulse > 0.001F) {
			MeritIcon.drawMoving(c, c.px(iconX), c.px(cy), c.px(d(28) * (1 + 0.2F * pulse)), 0xFFFFFFFF);
		} else {
			MeritIcon.draw(c, c.px(iconX), c.px(cy), c.px(d(28)), 0xFFFFFFFF);
		}
		MenuStyle.text(c, balance, iconX + d(14) + d(12), cy, Palette.lerp(MenuStyle.TEXT, GOLD_LIGHT, 0.4F + 0.6F * pulse));
	}

	/** The balance shown: counting up from nothing on opening, and from the old total after a claim (as every view does). */
	private long shownValue(RewardsClient.@Nullable Rewards data) {
		if (data == null) {
			return -2;
		}
		long value = rewards.shownBalance(System.currentTimeMillis());
		float k = momentAt >= 0 || dataAt < 0 || Kit.reduced() ? 1 : MenuStyle.appear(dataAt + 150, 950);
		return Math.round(value * (double) k);
	}

	/** One day: its card, the tick or TODAY or crown at the top left, the reward at the top right, the Merit, the day. */
	private void paintDay(Canvas c, int i, RewardsClient.Day day, RewardsClient.Rewards data, int todayDay, boolean can) {
		float cx = left + i * (cardW + gap);
		float rad = MenuStyle.radius();
		boolean claimed = data.streak() > 0 && day.day() <= data.streak();
		boolean isToday = can && day.day() == todayDay;
		boolean upcoming = !claimed && !isToday;
		long millis = Clock.millis();
		float pop = 0;
		if (momentAt >= 0 && day.day() == momentDay) {
			float t = Math.clamp((millis - momentAt) / (float) POP_MILLIS, 0, 1);
			pop = (float) Math.sin(Math.PI * t) * (1 - t * 0.3F);
		}
		boolean zoomed = pop > 0.001F;
		if (zoomed) {
			c.pushZoom(1 + 0.07F * pop, c.px(cx + cardW / 2), c.px(cardsY + cardH / 2));
		}
		try {
			int x0 = c.px(cx);
			int y0 = c.px(cardsY);
			int pw = c.px(cx + cardW) - x0;
			int ph = c.px(cardsY + cardH) - y0;
			int rp = c.px(rad);
			if (isToday) {
				float breathe = Kit.reduced() ? 0.6F : 0.5F + 0.5F * (float) Math.sin((millis - openedAt) / 520.0);
				halo(c, cx, cardsY, cardW, cardH, rad, 0.16F + 0.2F * breathe);
				MenuStyle.block(c, cx, cardsY, cardW, cardH, rad, 0, 0);
				// The orange coming up from the bottom of today's card.
				c.roundGradient(x0, y0 + ph / 3, pw, ph - ph / 3, rp, Palette.withAlpha(MenuStyle.ACCENT, 0),
					Palette.withAlpha(MenuStyle.ACCENT, 0.32F + 0.08F * breathe));
				sheen(c, cx, cardsY, cardW, cardH, millis);
				MenuStyle.ring(c, cx, cardsY, cardW, cardH, rad, Math.max(2, c.px(d(2))), MenuStyle.ACCENT);
			} else {
				MenuStyle.block(c, cx, cardsY, cardW, cardH, rad, 0, 0);
				if (claimed) {
					c.roundGradient(x0, y0 + ph / 2, pw, ph - ph / 2, rp, Palette.withAlpha(MenuStyle.ACCENT, 0),
						Palette.withAlpha(MenuStyle.ACCENT, 0.12F));
					MenuStyle.ring(c, cx, cardsY, cardW, cardH, rad, Math.max(1, c.px(d(1.25F))), Palette.withAlpha(MenuStyle.ACCENT, 0.38F));
				}
			}
			float inset = d(16);
			// Top left: the tick of a claimed day, TODAY on today's, a crown on the week's last day.
			if (claimed) {
				float k = momentAt >= 0 && day.day() == momentDay ? Math.clamp((millis - momentAt - POP_MILLIS * 0.35F) / 240.0F, 0, 1) : 1;
				float s = d(26);
				MenuStyle.box(c, cx + inset, cardsY + inset, s, s, s / 2, Palette.withAlpha(MenuStyle.ACCENT, k));
				Glyph.CHECK.draw(c, cx + inset + s / 2, cardsY + inset + s / 2, d(13) * (0.6F + 0.4F * k), Palette.withAlpha(0xFFFFFFFF, k));
			} else if (isToday) {
				float bw = today.width(c) + d(20);
				float bh = d(24);
				MenuStyle.box(c, cx + inset, cardsY + inset, bw, bh, bh / 2, MenuStyle.ACCENT);
				MenuStyle.centered(c, today, cx + inset + bw / 2, cardsY + inset + bh / 2 + d(0.5F), 0xFFFFFFFF, bw);
			} else if (day.day() % DAYS == 0) {
				Glyph.CROWN.draw(c, cx + inset + d(11), cardsY + inset + d(12), d(20), Palette.withAlpha(GOLD, 0.85F));
			}
			// Top right: the reward.
			int rewardInk = upcoming ? Palette.withAlpha(Palette.lerp(MenuStyle.NEUTRAL, GOLD_LIGHT, 0.25F), 0.8F) : GOLD_LIGHT;
			float rw = dayRewards[i].width(c);
			MenuStyle.text(c, dayRewards[i], cx + cardW - inset - rw, cardsY + inset + d(13), rewardInk);
			// The Merit, dimmed on the days still to come; today's floats gently.
			int iconPx = MeritIcon.crispSize(c.px(Math.min(cardW * 0.44F, cardH * 0.38F)));
			float bob = isToday && !Kit.reduced() ? (float) Math.sin((millis - openedAt) / 640.0) * d(3) : 0;
			MeritIcon.draw(c, c.px(cx + cardW / 2), c.px(cardsY + cardH * 0.5F + bob), iconPx, upcoming ? 0x73FFFFFF : 0xFFFFFFFF);
			if (isToday) {
				twinkles(c, cx, cardsY, cardW, cardH, millis);
			}
			// The day at the bottom.
			int nameInk = upcoming ? MenuStyle.NEUTRAL : MenuStyle.TEXT;
			MenuStyle.centered(c, dayNames[i], cx + cardW / 2, cardsY + cardH - d(26), nameInk, cardW - d(16));
		} finally {
			if (zoomed) {
				c.popOffset();
			}
		}
	}

	/** The segment under a day: the accent once claimed, half lit on today, grey to come; it fills in as the streak arrives. */
	private void paintSegment(Canvas c, int i, RewardsClient.Day day, RewardsClient.Rewards data, boolean can) {
		float sx = left + i * (cardW + gap);
		float th = d(8);
		MenuStyle.box(c, sx, segY, cardW, th, th / 2, MenuStyle.SOFT_ON_BLOCK);
		boolean claimed = data.streak() > 0 && day.day() <= data.streak();
		boolean isToday = can && day.day() == data.nextDay();
		if (!claimed && !isToday) {
			return;
		}
		float k = 1;
		if (momentAt >= 0 && day.day() == momentDay) {
			k = Math.clamp((Clock.millis() - momentAt) / 600.0F, 0, 1);
		} else if (dataAt >= 0 && !Kit.reduced()) {
			k = MenuStyle.appear(dataAt + 220 + i * 70L, 520);
		}
		float fw = Math.max(th, cardW * (1 - (1 - k) * (1 - k)));
		int x0 = c.px(sx);
		int y0 = c.px(segY);
		int top = claimed ? Palette.lerp(MenuStyle.ACCENT, 0xFFFFFFFF, 0.12F) : Palette.withAlpha(MenuStyle.ACCENT, 0.55F);
		int low = claimed ? MenuStyle.ACCENT : Palette.withAlpha(MenuStyle.ACCENT, 0.4F);
		c.roundGradient(x0, y0, c.px(sx + fw) - x0, Math.max(1, c.px(segY + th) - y0), c.px(th / 2), top, low);
	}

	/** SPEND YOUR MERITS: what Merits will buy, in a banner (the Store isn't open yet). */
	private void paintSpend(Canvas c) {
		big(c, spendTitle, left, spendY + d(12), MenuStyle.WORDMARK);
		float chipW = soon.width(c) + d(28);
		float chipH = d(30);
		float chipX = right - chipW;
		MenuStyle.box(c, chipX, spendY + d(12) - chipH / 2, chipW, chipH, MenuStyle.radius(), Palette.withAlpha(MenuStyle.ACCENT, 0.14F));
		MenuStyle.centered(c, soon, chipX + chipW / 2, spendY + d(12), MenuStyle.ACCENT, chipW);
		float rad = MenuStyle.radius();
		MenuStyle.block(c, left, bannerY, right - left, bannerH, rad, 0, 0);
		int x0 = c.px(left);
		int y0 = c.px(bannerY);
		int pw = c.px(right) - x0;
		int ph = c.px(bannerY + bannerH) - y0;
		// A warm light from the banner's left, behind its Merits.
		c.roundLinear(x0, y0, pw, ph, c.px(rad), 0, new int[] {Palette.withAlpha(MenuStyle.ACCENT, 0.16F), Palette.withAlpha(MenuStyle.ACCENT, 0)},
			Math.max(2, pw / 48));
		float art = Math.min(bannerH - d(36), d(128));
		float ax = left + d(28) + art / 2;
		float ay = bannerY + bannerH / 2;
		long t = Clock.millis() - openedAt;
		float drift = Kit.reduced() ? 0 : (float) Math.sin(t / 900.0) * d(3);
		MeritIcon.draw(c, c.px(ax - art * 0.34F), c.px(ay + art * 0.12F - drift), MeritIcon.crispSize(c.px(art * 0.5F)), 0x99FFFFFF);
		MeritIcon.draw(c, c.px(ax + art * 0.34F), c.px(ay + art * 0.16F + drift), MeritIcon.crispSize(c.px(art * 0.44F)), 0x80FFFFFF);
		MeritIcon.draw(c, c.px(ax), c.px(ay - art * 0.08F + drift * 0.5F), MeritIcon.crispSize(c.px(art * 0.72F)), 0xFFFFFFFF);
		float tx = left + d(28) + art + d(28);
		float tw = right - d(28) - tx;
		MenuStyle.fitted(c, bannerTitle, tx, ay - d(26), MenuStyle.TEXT, tw);
		MenuStyle.fitted(c, bannerNote, tx, ay + d(2), MenuStyle.INFO, tw);
		String refused = rewards.refusal(System.currentTimeMillis());
		RewardsClient.Rewards shown = rewards.rewards();
		bannerRule.set(refused != null ? refusalText(refused)
			: shown != null && shown.maxReward() > 0 ? "Come back every day: rewards grow through the week, up to " + shown.maxReward() + " Merits."
			: "Come back every day: rewards grow through the week.");
		MenuStyle.fitted(c, bannerRule, tx, ay + d(30), refused != null ? MenuStyle.TEXT : Palette.lerp(MenuStyle.NEUTRAL, GOLD, 0.45F), tw);
	}

	/** A slow band of light crossing today's card every three seconds. */
	private void sheen(Canvas c, float cx, float cy, float cw, float ch, long millis) {
		if (Kit.reduced()) {
			return;
		}
		float phase = ((millis - openedAt) % 3000) / 3000.0F;
		if (phase > 0.55F) {
			return;
		}
		float t = phase / 0.55F;
		int x0 = c.px(cx);
		int y0 = c.px(cy);
		int x1 = c.px(cx + cw);
		int y1 = c.px(cy + ch);
		int band = Math.max(4, c.px(cw * 0.35F));
		int at = x0 - band + Math.round((x1 - x0 + 2 * band) * t);
		c.pushClip(x0, y0, x1, y1);
		try {
			int step = Math.max(1, band / 16);
			for (int i = 0; i < band; i += step) {
				float a = (float) Math.sin(Math.PI * i / band) * 0.12F;
				c.fill(at + i, y0, at + i + step, y1, Palette.withAlpha(0xFFFFE6C8, a));
			}
		} finally {
			c.popClip();
		}
	}

	/** Three small Merits twinkling around today's Merit, each in its own time. */
	private static void twinkles(Canvas c, float cx, float cy, float cw, float ch, long millis) {
		if (Kit.reduced()) {
			return;
		}
		float[][] at = {{cx + cw * 0.24F, cy + ch * 0.36F}, {cx + cw * 0.78F, cy + ch * 0.58F}, {cx + cw * 0.70F, cy + ch * 0.30F}};
		for (int i = 0; i < at.length; i++) {
			float phase = ((millis + i * 700L) % 2100) / 2100.0F;
			float s = (float) Math.sin(Math.PI * phase);
			if (s < 0.05F) {
				continue;
			}
			MeritIcon.drawMoving(c, c.px(at[i][0]), c.px(at[i][1]), Math.max(3, c.px(d(13) * s)), Palette.withAlpha(0xFFFFFFFF, s));
		}
	}

	/** A soft glow around a card: rings in the accent fading outward. */
	private static void halo(Canvas c, float x, float y, float w, float h, float radius, float alpha) {
		for (int k = 1; k <= 4; k++) {
			float out = d(2.5F * k);
			MenuStyle.ring(c, x - out, y - out, w + 2 * out, h + 2 * out, radius + out, Math.max(1, c.px(d(2.5F))),
				Palette.withAlpha(MenuStyle.ACCENT, alpha * (1 - k / 5.0F) * 0.6F));
		}
	}

	/** No rewards to show yet: shimmering cards while loading, or why there are none. */
	private void paintWithout(Canvas c) {
		DailyRewards.State state = rewards.state();
		float t = Clock.millis() - openedAt;
		for (int i = 0; i < DAYS; i++) {
			float cx = left + i * (cardW + gap);
			float before = c.opacity();
			float wave = Kit.reduced() || state != DailyRewards.State.LOADING ? 0 : (float) Math.sin(t / 300.0 - i * 0.5);
			c.setOpacity(before * (state == DailyRewards.State.LOADING ? 0.55F + 0.3F * wave : 0.35F));
			MenuStyle.block(c, cx, cardsY, cardW, cardH, MenuStyle.radius(), 0, 0);
			MenuStyle.box(c, cx, segY, cardW, d(8), d(4), MenuStyle.SOFT_ON_BLOCK);
			c.setOpacity(before);
		}
		switch (state) {
			case LOADING -> {
				message.set("Getting your rewards…");
				messageNote.set("One moment while Flint checks your account.");
			}
			case NO_ACCOUNT -> {
				message.set("Rewards need a Microsoft account");
				messageNote.set("Sign in with Microsoft in the Flint app and play with that account to collect Merits.");
			}
			case OFFLINE -> {
				message.set("Can't reach Flint right now");
				messageNote.set("Check your connection. Flint tries again by itself.");
			}
			case PAUSED -> {
				String why = rewards.closedReason();
				if ("locked".equals(why)) {
					message.set("Rewards are on hold for this account");
					messageNote.set("Contact Flint support if you think this is a mistake.");
				} else if ("account_too_new".equals(why)) {
					message.set("Rewards open a little later for new accounts");
					messageNote.set("Keep playing with this account and check back in a few days.");
				} else {
					message.set("Daily rewards are paused");
					messageNote.set("Flint has them switched off for now. This page updates by itself when they're back.");
				}
			}
			case STORE_ACCOUNT -> {
				message.set("Sign in to the Flint Store first");
				messageNote.set("Open the Store page in the Flint app and sign in once with this account, then collect Merits here.");
			}
			default -> {
				message.set("Daily rewards aren't in this build");
				messageNote.set("Update Flint to collect Merits every day.");
			}
		}
		float my = bannerY + bannerH / 2;
		MeritIcon.draw(c, c.px((left + right) / 2), c.px(my - d(56)), c.px(d(46)), state == DailyRewards.State.LOADING ? 0xFFFFFFFF : 0x99FFFFFF);
		MenuStyle.centered(c, message, (left + right) / 2, my, MenuStyle.EMPTY_TEXT, right - left);
		MenuStyle.centered(c, messageNote, (left + right) / 2, my + d(30), MenuStyle.EMPTY_NOTE, right - left);
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

	/** Remakes the texts whose numbers changed. */
	private void refreshLabels(RewardsClient.@Nullable Rewards data) {
		long value = shownValue(data);
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
				dayRewards[i].set(day == null ? "" : "+" + day.reward());
			}
		}
		if (data.streak() != shownStreak) {
			shownStreak = data.streak();
			streakValue.set(Integer.toString(data.streak()));
		}
		if (data.best() != shownBest) {
			shownBest = data.best();
			bestValue.set(Integer.toString(data.best()));
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

	/** How far entrance {@code step} has come (0 to 1): the cards one after another, then the section under them. */
	private float enter(int step) {
		if (Kit.reduced()) {
			return 1;
		}
		return dataAt < 0 ? 0 : MenuStyle.appear(dataAt + 40 + step * 55L, 460);
	}

	/** Begins drawing something entering: faded and lowered by how far it has to come. Returns the opacity to restore. */
	private static float entering(Canvas c, float in) {
		float before = c.opacity();
		if (in < 1) {
			c.pushOffset(0, Math.round((1 - in) * d(18) * c.scale()));
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

	/** A claim that just succeeded starts the moment once: the chime, the card, the Merits flying, the count. */
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
		float t = (Clock.millis() - momentAt - FLIGHT_MILLIS * 0.6F) / (float) (COUNT_MILLIS + 300);
		return t <= 0 || t >= 1 ? 0 : (float) Math.sin(Math.PI * t);
	}

	/** The Merits flying into the balance and the "+15" rising from the day's card, over everything. */
	@Override
	protected void paintOver(Canvas c) {
		if (momentAt < 0) {
			return;
		}
		long since = Clock.millis() - momentAt;
		if (since > FLIGHT_MILLIS + SPARKS * FLIGHT_STAGGER + COUNT_MILLIS + 400) {
			momentAt = -1;
			return;
		}
		float sx = claim.x() + claim.width() / 2;
		float sy = claim.y() + claim.height() / 2;
		float ex = balanceX + d(18) + d(14);
		float ey = headY + d(HEAD) / 2;
		if (!Kit.reduced()) {
			for (int i = 0; i < SPARKS; i++) {
				float t = (since - i * FLIGHT_STAGGER) / (float) FLIGHT_MILLIS;
				if (t <= 0 || t >= 1) {
					continue;
				}
				float e = t < 0.5F ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
				// Each Merit leaves the button in its own direction (downward first), then curves round into the balance.
				double angle = Math.PI * (0.15 + 0.7 * ((i * 7) % SPARKS) / (double) SPARKS);
				float spread = d(110 + 40 * (i % 3));
				float cx1 = sx - (float) Math.cos(angle) * spread;
				float cy1 = sy + (float) Math.sin(angle) * spread;
				float u = 1 - e;
				float px = u * u * sx + 2 * u * e * cx1 + e * e * ex;
				float py = u * u * sy + 2 * u * e * cy1 + e * e * ey;
				float size = d(26) * (1 - 0.45F * e);
				float alpha = Math.min(1, t * 6) * (t > 0.85F ? (1 - t) / 0.15F : 1);
				MeritIcon.drawMoving(c, c.px(px), c.px(py), c.px(size), Palette.withAlpha(0xFFFFFFFF, alpha));
			}
		}
		int index = momentDay - shownFirstDay;
		if (index < 0 || index >= DAYS) {
			return;
		}
		float rise = Math.clamp(since / 1100.0F, 0, 1);
		float fade = since < 900 ? Math.min(1, since / 140.0F) : Math.max(0, 1 - (since - 900) / 450.0F);
		if (fade > 0) {
			float px = left + index * (cardW + gap) + cardW / 2;
			float py = cardsY + cardH * 0.3F - d(46) * (1 - (1 - rise) * (1 - rise));
			float pw = plus.width(c);
			c.pushZoom(2, c.px(px), c.px(py));
			try {
				MenuStyle.centered(c, plus, px, py, Palette.withAlpha(GOLD_LIGHT, fade), pw + d(10));
			} finally {
				c.popOffset();
			}
		}
	}

	// ---------- the button ----------

	/**
	 * Claim: an orange button with the Merit, "Claim Daily Reward", lifting a touch under the pointer, a slow sheen crossing
	 * it; while the claim travels it says so; once claimed it is a quiet panel counting down to the next reward.
	 */
	private final class ClaimButton extends Pressable {
		private final Label ready = new Label(Type.HUB_BUTTON, "Claim Daily Reward");
		private final Label working = new Label(Type.HUB_BUTTON, "Claiming…");
		private final Label countdown = new Label(Type.PILL, "");
		private final Label nextCaption = new Label(Type.PILL_NOTE, "Next reward in");
		private long countdownFor = -1;
		private float lit = -1;

		ClaimButton() {
			super(null);
			setAction(() -> rewards.claim(System.currentTimeMillis()));
			name("Claim daily reward");
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
			RewardsClient.Rewards data = rewards.rewards();
			if (data == null) {
				return;
			}
			long now = System.currentTimeMillis();
			boolean can = rewards.claimable(now);
			boolean busy = rewards.claiming();
			lit = MenuStyle.ease(lit, can || busy);
			float rad = MenuStyle.radius();
			float lift = can && !busy && !Kit.reduced() ? hover * 0.015F - press * 0.012F : 0;
			if (lift != 0) {
				c.pushZoom(1 + lift, c.px(x + w / 2), c.px(y + h / 2));
			}
			try {
				if (lit > 0.01F) {
					float before = c.opacity();
					c.setOpacity(before * lit);
					paintReady(c, rad, busy);
					c.setOpacity(before);
				}
				if (lit < 0.99F) {
					float before = c.opacity();
					c.setOpacity(before * (1 - lit));
					paintWaiting(c, rad, now);
					c.setOpacity(before);
				}
				MenuStyle.focusRing(c, x, y, w, h, rad, focus);
			} finally {
				if (lift != 0) {
					c.popOffset();
				}
			}
		}

		private void paintReady(Canvas c, float rad, boolean busy) {
			float glow = busy ? 0 : hover;
			for (int k = 1; k <= 3; k++) {
				float out = d(2.5F * k);
				MenuStyle.ring(c, x - out, y - out, w + 2 * out, h + 2 * out, rad + out, Math.max(1, c.px(d(2.5F))),
					Palette.withAlpha(MenuStyle.ACCENT, (0.10F + 0.22F * glow) * (1 - k / 4.0F)));
			}
			int top = Palette.lerp(Palette.lerp(MenuStyle.ACCENT, 0xFFFFFFFF, 0.14F + 0.08F * glow), 0xFF000000, press * 0.18F);
			int bottom = Palette.lerp(Palette.lerp(MenuStyle.ACCENT, 0xFF000000, 0.06F), 0xFF000000, press * 0.25F);
			int x0 = c.px(x);
			int y0 = c.px(y);
			int x1 = c.px(x + w);
			int y1 = c.px(y + h);
			c.roundGradient(x0, y0, x1 - x0, y1 - y0, c.px(rad), top, bottom);
			if (!busy && !Kit.reduced()) {
				float phase = ((Clock.millis() - openedAt) % 3200) / 3200.0F;
				int sw = c.px(d(60));
				int sx = c.px(x - w * 0.3F + w * 1.6F * phase);
				c.pushClip(x0, y0, x1, y1);
				try {
					int step = Math.max(1, sw / 24);
					for (int i = 0; i < sw; i += step) {
						float a = (float) Math.sin(Math.PI * i / sw) * 0.2F;
						c.fill(sx + i, y0, sx + i + step, y1, Palette.withAlpha(0xFFFFFFFF, a));
					}
				} finally {
					c.popClip();
				}
			}
			Label label = busy ? working : ready;
			float icon = Math.min(d(30), h * 0.5F);
			float total = icon + d(12) + label.width(c);
			float lx = x + (w - total) / 2;
			float cy = y + h / 2 + press * d(1);
			MeritIcon.draw(c, c.px(lx + icon / 2), c.px(cy), c.px(icon), busy ? 0xB3FFFFFF : 0xFFFFFFFF);
			MenuStyle.text(c, label, lx + icon + d(12), cy + d(1), 0xFFFFFFFF);
		}

		private void paintWaiting(Canvas c, float rad, long now) {
			MenuStyle.block(c, x, y, w, h, rad, 0, 0);
			long left = rewards.untilNext(now) / 1000;
			if (left != countdownFor) {
				countdownFor = left;
				countdown.set(String.format(Locale.ROOT, "%d:%02d:%02d", left / 3600, left / 60 % 60, left % 60));
			}
			float cy = y + h / 2;
			Glyph.CLOCK.draw(c, x + d(28), cy, d(20), MenuStyle.ACCENT);
			float tx = x + d(28) + d(10) + d(14);
			MenuStyle.text(c, nextCaption, tx, cy - d(10), MenuStyle.NEUTRAL);
			MenuStyle.text(c, countdown, tx, cy + d(12), MenuStyle.TEXT);
		}
	}
}

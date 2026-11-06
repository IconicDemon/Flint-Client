package com.flintclient.client.ui.widget;

import com.flintclient.client.control.feature.DailyRewards;
import com.flintclient.client.control.store.RewardsClient;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.MeritIcon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.kit.Kit;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * The main menu's "Daily rewards" card, above "What's new": today's reward waiting ("Claim 15 Merits", the card breathing in
 * the Merit's gold), or the time to the next one, with the streak and the balance; opens the Daily Rewards window.
 * Its texts are remade only when what they say changes (the countdown once a minute).
 */
public final class RewardsCard extends FlintButton {
	private static final int GOLD = 0xFFE3B23C;
	private static final int GOLD_LIGHT = 0xFFFCE7A1;

	private final DailyRewards rewards = DailyRewards.get();
	private final TextLine heading;
	private final TextLine title = new TextLine(FlintFonts.STRONG, "");
	private final TextLine summary = new TextLine(FlintFonts.STRONG, "");
	private final TextLine badge = new TextLine(FlintFonts.SMALL, "");
	private String badgeText = "";
	private boolean summaryGold;
	private String titleText = "";
	private String summaryText = "";
	private final long shownAt = Clock.millis();

	public RewardsCard(Runnable action) {
		super(Component.translatable("flintclient.rewards.open"), action);
		this.heading = new TextLine(FlintFonts.CAPS, Component.translatable("flintclient.rewards.heading").getString().toUpperCase(Locale.ROOT), 0.22F);
	}

	@Override
	protected void paint(Canvas c) {
		long now = System.currentTimeMillis();
		rewards.update(now);
		boolean waiting = rewards.claimable(now);
		refresh(now, waiting);

		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover * 0.7F);
		int border = Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover);
		float breathe = 0;
		if (waiting) {
			breathe = Kit.reduced() ? 0.7F : 0.55F + 0.45F * (float) Math.sin((Clock.millis() - shownAt) / 520.0);
			border = Palette.lerp(border, GOLD, 0.55F + 0.35F * breathe);
		}
		border = Palette.lerp(border, Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, c.px(3.2F), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);
		if (waiting) {
			// A soft gold light from the top of the card.
			c.roundGradient(x + borderPx, y + borderPx, w - 2 * borderPx, (h - 2 * borderPx) / 2, Math.max(0, c.px(3.2F) - borderPx),
				Palette.withAlpha(GOLD, 0.10F + 0.06F * breathe), Palette.withAlpha(GOLD, 0));
		}

		// The Merit on the left, floating gently while a reward waits, its "+15" at its top right.
		int pad = c.px(NewsCard.PAD_X);
		int icon = MeritIcon.crispSize(Math.max(1, h - 2 * c.px(7.5F)));
		int iconCx = x + pad + icon / 2;
		float bob = waiting && !Kit.reduced() ? (float) Math.sin((Clock.millis() - shownAt) / 700.0) * c.px(0.6F) : 0;
		MeritIcon.draw(c, iconCx, y + h / 2 + Math.round(bob), icon, waiting ? 0xFFFFFFFF : 0xB3FFFFFF);
		if (waiting && !badgeText.isEmpty()) {
			int bw = c.width(badge) + c.px(3);
			int bh = c.px(6.5F);
			int bx = iconCx + icon / 2 - bw + c.px(1);
			int by = y + c.px(3.5F);
			c.roundRect(bx, by, bw, bh, bh / 2, 0xE6140B04);
			c.text(badge, bx + c.px(1.5F), c.baselineFor(badge, by + bh / 2), GOLD_LIGHT);
		}
		int left = iconCx + icon / 2 + c.px(6);
		int inner = x + w - pad - left;
		c.text(heading, left, y + c.px(8.6F), waiting ? GOLD_LIGHT : Palette.ACCENT_TEXT);
		c.text(title, left, y + c.px(17.2F), Palette.TEXT, inner);
		c.text(summary, left, y + c.px(25.6F), summaryGold ? GOLD_LIGHT : Palette.lerp(Palette.MUTED, Palette.TEXT_DIM, hover), inner);
	}

	/** What the card says now. */
	private void refresh(long now, boolean waiting) {
		RewardsClient.Rewards data = rewards.rewards();
		String t;
		String s;
		if (data != null) {
			if (waiting) {
				t = "Check in now";
				s = "for " + data.nextReward() + " Merits!";
			} else {
				long minutes = (rewards.untilNext(now) + 59_999) / 60_000;
				t = minutes >= 60 ? "Back in " + minutes / 60 + "h " + minutes % 60 + "m" : "Back in " + Math.max(1, minutes) + "m";
				s = (data.streak() > 0 ? data.streak() + " day streak · " : "") + String.format(Locale.ROOT, "%,d", rewards.shownBalance(now)) + " Merits";
			}
			summaryGold = waiting;
			String b = "+" + data.nextReward();
			if (!b.equals(badgeText)) {
				badgeText = b;
				badge.set(b);
			}
		} else {
			summaryGold = false;
			switch (rewards.state()) {
				case NO_ACCOUNT -> {
					t = "Merits every day";
					s = "Needs a Microsoft account";
				}
				case OFFLINE -> {
					t = "Merits every day";
					s = "Can't reach Flint right now";
				}
				case UNAVAILABLE, PAUSED -> {
					t = "Merits every day";
					s = "Coming soon to Flint";
				}
				case STORE_ACCOUNT -> {
					t = "Merits every day";
					s = "Sign in to the Flint Store first";
				}
				default -> {
					t = "Merits every day";
					s = "Getting your rewards…";
				}
			}
		}
		if (!t.equals(titleText)) {
			titleText = t;
			title.set(t);
		}
		if (!s.equals(summaryText)) {
			summaryText = s;
			summary.set(s);
		}
	}
}

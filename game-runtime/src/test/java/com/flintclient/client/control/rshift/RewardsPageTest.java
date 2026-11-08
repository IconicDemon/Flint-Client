package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RewardsPageTest {
	@Test
	void everyRefusalHasAShorterLineForNarrowWindows() {
		for (String code : new String[] {"ip_daily_limit", "daily_cap_reached", "disabled", "store_account_required", "something_new"}) {
			String full = RewardsPage.refusalText(code);
			String brief = RewardsPage.refusalShort(code);
			assertFalse(brief.isBlank(), code);
			assertTrue(brief.length() < full.length(), code + ": the short line is shorter");
		}
	}

	@Test
	void theClaimButtonsInkReadsOnTheAccent() {
		int was = MenuStyle.ACCENT;
		try {
			// Flint's orange (as the Store's buttons): dark ink.
			MenuStyle.ACCENT = 0xFFF97316;
			assertTrue(luminance(RewardsPage.onAccent()) < 0.1F, "dark ink on Flint's orange");
			// Bloodstone's deep crimson: the theme's light text.
			MenuStyle.ACCENT = 0xFFAC383E;
			assertEquals(MenuStyle.TEXT, RewardsPage.onAccent());
		} finally {
			MenuStyle.ACCENT = was;
		}
	}

	@Test
	void theWaitFillsOverTheDayBetweenClaims() {
		assertEquals(0F, RewardsPage.waitProgress(86_400_000L));
		assertEquals(0.5F, RewardsPage.waitProgress(43_200_000L), 1e-4F);
		assertEquals(1F, RewardsPage.waitProgress(0));
		// A longer wait than a day (a clock ahead) or a late answer stays in the line.
		assertEquals(0F, RewardsPage.waitProgress(90_000_000L));
		assertEquals(1F, RewardsPage.waitProgress(-5_000));
	}

	@Test
	void theStreakAndTheBestReadAsDays() {
		assertEquals("No streak yet", RewardsPage.streakText(0));
		assertEquals("Streak: 1 day", RewardsPage.streakText(1));
		assertEquals("Streak: 3 days", RewardsPage.streakText(3));
		assertEquals("Best: 9 days", RewardsPage.bestText(9));
		assertEquals("Best: 1 day", RewardsPage.bestText(1));
	}

	@Test
	void theCountdownShowsHoursMinutesAndSeconds() {
		assertEquals("19:57:05", RewardsPage.countdown(((19 * 60 + 57) * 60 + 5) * 1000L + 400));
		assertEquals("0:00:00", RewardsPage.countdown(-2_000));
		assertEquals("24:00:00", RewardsPage.countdown(86_400_000L));
	}

	@Test
	void theLineBetweenTheDaysFillsOneStretchPerDayClaimed() {
		// Three days claimed: the stretches 1-2 and 2-3 full, 3-4 empty.
		assertEquals(1F, RewardsPage.linkFill(3, 0));
		assertEquals(1F, RewardsPage.linkFill(3, 1));
		assertEquals(0F, RewardsPage.linkFill(3, 2));
		// Day 4 being claimed: 3-4 half way.
		assertEquals(0.5F, RewardsPage.linkFill(3.5F, 2), 1e-4F);
		assertEquals(0F, RewardsPage.linkFill(0, 0));
	}

	private static float luminance(int argb) {
		return (0.2126F * ((argb >> 16) & 0xFF) + 0.7152F * ((argb >> 8) & 0xFF) + 0.0722F * (argb & 0xFF)) / 255.0F;
	}
}

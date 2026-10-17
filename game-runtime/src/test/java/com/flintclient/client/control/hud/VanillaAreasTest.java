package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VanillaAreasTest {
	@Test
	void hotbarAndStatusBarsSitAtTheBottomCentre() {
		assertEquals(new HudRect(69, 218, 182, 22), VanillaAreas.hotbar(320, 240));
		assertEquals(new HudRect(122, 218, 182, 22), VanillaAreas.hotbar(427, 240), "the width is halved in whole units first");
		assertEquals(new HudRect(69, 191, 182, 27), VanillaAreas.statusBars(320, 240), "hearts 39 up, armour and air a row higher");
	}

	@Test
	void bossBarsStackDownFromTheTop() {
		assertEquals(new HudRect(149, 3, 182, 14), VanillaAreas.bossBars(480, 1), "name at 3, bar at 12, 5 tall");
		assertEquals(new HudRect(149, 3, 182, 33), VanillaAreas.bossBars(480, 2), "19 units per bar");
	}

	@Test
	void chatFollowsItsOptions() {
		assertEquals(new HudRect(0, 110, 332, 90), VanillaAreas.chat(240, VanillaAreas.DEFAULT_CHAT_WIDTH, VanillaAreas.DEFAULT_CHAT_HEIGHT, 1.0F));
		assertEquals(new HudRect(0, 65, 326, 45), VanillaAreas.chat(150, 320, 90, 0.5F), "smaller lines, but text still wraps at the width");
	}

	@Test
	void scoreboardAndEffects() {
		// 10 scores 9 units apart: bottom at 120 + 30, a title row above.
		assertEquals(new HudRect(375, 50, 104, 100), VanillaAreas.sidebar(480, 240, 100, 10));
		assertEquals(new HudRect(375, 20, 104, 145), VanillaAreas.sidebar(480, 240, 100, 40), "at most 15 lines");
		assertEquals(new HudRect(430, 1, 49, 24), VanillaAreas.effects(480, 2, 0));
		assertEquals(new HudRect(405, 1, 74, 50), VanillaAreas.effects(480, 1, 3));
	}
}

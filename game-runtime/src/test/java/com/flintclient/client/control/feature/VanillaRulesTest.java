package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The calculated readouts the 1.0.4 audit fixed, against Minecraft's own rules: ticks as seconds at a server's tick rate
 * (TNT Countdown, Cooldowns, Momentum) and the saturation eating really adds (Saturation's gain preview).
 */
class VanillaRulesTest {
	@Test
	void ticksAreSecondsAtTheWorldsTickRate() {
		// A lit TNT's fuse: 80 ticks.
		assertEquals(4.0, GameTime.seconds(80, 20), 1e-9);
		// A server's "/tick rate 10" makes everything count down at half speed.
		assertEquals(8.0, GameTime.seconds(80, 10), 1e-9);
		// An ender pearl's cooldown: 20 ticks.
		assertEquals(1.0, GameTime.seconds(20, 20), 1e-9);
		assertEquals(0, GameTime.seconds(-3, 20), "a fuse past zero shows 0");
		assertEquals(4.0, GameTime.seconds(80, 0), 1e-9, "no tick rate counts as 20");
		assertEquals(4.0, GameTime.seconds(80, Float.NaN), 1e-9);
	}

	@Test
	void aLegacyTntExplodesATickAfterItsFuseRunsOut() {
		// 1.8.9's PrimedTntEntity.tick: "if (fuseTimer-- <= 0) explode": a fresh one (80) goes off on its 81st tick.
		int fuse = 80;
		int ticks = 0;
		while (true) {
			ticks++;
			if (fuse-- <= 0) {
				break;
			}
		}
		assertEquals(81, ticks);
		assertEquals(81 / 20.0, GameTime.legacyFuseSeconds(80, 0), 1e-6);
		assertEquals(0.05, GameTime.legacyFuseSeconds(0, 0), 1e-6);
		assertEquals(0, GameTime.legacyFuseSeconds(0, 1), 1e-6);
		// 26.x / 1.19+ (PrimedTnt.tick: "fuse - 1 <= 0"): a fresh one goes off on its 80th tick.
		fuse = 80;
		ticks = 0;
		while (true) {
			ticks++;
			if (--fuse <= 0) {
				break;
			}
		}
		assertEquals(80, ticks);
		assertEquals(80 / 20.0, GameTime.seconds(80, 20), 1e-6);
	}

	@Test
	void daysAndClockTimesAsMinecraftCountsThem() {
		// TimeCommand's "query day": dayTime / 24000, so a new world is on day 0 (Day Counter, the minimap).
		assertEquals(0, GameTime.day(0));
		assertEquals(0, GameTime.day(23_999));
		assertEquals(1, GameTime.day(24_000));
		assertEquals(41, GameTime.day(41 * 24_000L + 6000));
		// Tick 0 is sunrise at 6:00, 6000 noon, 12000 18:00, 18000 midnight.
		assertEquals(6 * 60, GameTime.clockMinutes(0));
		assertEquals(12 * 60, GameTime.clockMinutes(6000));
		assertEquals(18 * 60, GameTime.clockMinutes(12_000));
		assertEquals(0, GameTime.clockMinutes(18_000));
		assertEquals(6 * 60 + 59, GameTime.clockMinutes(999));
		assertEquals(6 * 60, GameTime.clockMinutes(5 * 24_000L));
	}

	@Test
	void speedFollowsTheTickRate() {
		SpeedTracker t = new SpeedTracker();
		t.sample(0, 64, 0, 10);
		t.sample(0.2806, 64, 0, 10);
		assertEquals(2.806, t.speed(true, false), 1e-9, "a tick's 0.2806 blocks at 10 ticks a second");
		t.reset();
		t.sample(0, 64, 0, 0);
		t.sample(0.2806, 64, 0, 0);
		assertEquals(5.612, t.speed(true, false), 1e-9, "a broken rate counts as 20");
	}

	@Test
	void saturationGainIsCappedAtTheNewFoodLevel() {
		// FoodData.add: food = clamp(food + nutrition, 0, 20); saturation = clamp(saturation + gain, 0, food).
		// A golden carrot (6, 14.4) at 15 food and 10 saturation: food 20, saturation 20 (not 21), so 10 more.
		assertEquals(10, FoodRegen.saturationGained(15, 10, 6, 14.4F), 1e-6);
		// Steak (8, 12.8) at 4 food and 0 saturation: food 12, saturation 12 (capped at the food level).
		assertEquals(12, FoodRegen.saturationGained(4, 0, 8, 12.8F), 1e-6);
		// Bread (5, 6) at 10 food and 2 saturation: all of it.
		assertEquals(6, FoodRegen.saturationGained(10, 2, 5, 6), 1e-6);
		// At a full bar with full saturation nothing is added.
		assertEquals(0, FoodRegen.saturationGained(20, 20, 4, 9.6F), 1e-6);
	}
}

package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Elytra flight time and Unbreaking, against Minecraft's own rules (ItemWear's notes): the owner's report was an elytra
 * with Unbreaking III showing the same "Flight left" as one without.
 */
class ItemWearTest {
	private static final int ELYTRA = 432;

	private static double flight(int damage, int unbreaking) {
		return ItemWear.flightSeconds(ELYTRA, damage, true, unbreaking, false, false, 20);
	}

	@Test
	void unbreakingThreeLastsFourTimesAsLongAsNone() {
		String plain = ItemWear.flightLine(flight(0, 0), 0, false);
		String three = ItemWear.flightLine(flight(0, 3), 3, false);
		assertNotEquals(plain, three, "Unbreaking III must change the flight time");
		// 431 usable points, one a second.
		assertEquals("Flight left: 7m 11s", plain);
		// A point is lost 1 time in 4: 1724 s on average.
		assertEquals("Flight left: ~28m 44s", three);
		assertEquals(1724, flight(0, 3), 1e-9);
		assertEquals(862, flight(0, 1), 1e-9);
		assertEquals(1293, flight(0, 2), 1e-9);
	}

	@Test
	void unbreakingChancesMatchTheEnchantmentData() {
		// unbreaking.json: removed with L/(L+1), or 2L/(10+5(L-1)) for #enchantable/armor.
		assertEquals(1, ItemWear.lossChance(0, false));
		assertEquals(1, ItemWear.lossChance(0, true));
		assertEquals(1 / 2.0, ItemWear.lossChance(1, false), 1e-12);
		assertEquals(1 / 4.0, ItemWear.lossChance(3, false), 1e-12);
		assertEquals(0.8, ItemWear.lossChance(1, true), 1e-12);
		assertEquals(0.6 + 0.4 / 4, ItemWear.lossChance(3, true), 1e-12);
		// Armor-type items would get the gentler rule; an elytra doesn't on any line.
		assertEquals(431 / 0.7, ItemWear.flightSeconds(ELYTRA, 0, true, 3, true, false, 20), 1e-9);
	}

	@Test
	void unbreakingChancesMatchMinecraftsOwnDiceRolls() {
		// 1.19.x's DigDurabilityEnchantment.shouldIgnoreDurabilityDrop, rolled; 1.21's remove_binomial is the same chance.
		Random random = new Random(1234);
		for (boolean armor : new boolean[] {false, true}) {
			for (int level = 1; level <= 5; level++) {
				int trials = 400_000;
				int lost = 0;
				for (int i = 0; i < trials; i++) {
					boolean ignored = armor && random.nextFloat() < 0.6F ? false : random.nextInt(level + 1) > 0;
					if (!ignored) {
						lost++;
					}
				}
				assertEquals(ItemWear.lossChance(level, armor), lost / (double) trials, 0.005, "armor " + armor + ", level " + level);
			}
		}
	}

	@Test
	void glidingWearsAPointEveryTwentyTicksAndStopsBeforeBreaking() {
		// LivingEntity.updateFallFlying: i = fallFlyTicks + 1; every i % 10 == 0 with (i / 10) % 2 == 0 costs a point;
		// gliding needs damage < max - 1.
		int damage = 400;
		int ticks = 0;
		while (damage < ELYTRA - 1) {
			ticks++;
			if (ticks % 10 == 0 && ticks / 10 % 2 == 0) {
				damage++;
			}
		}
		assertEquals(ItemWear.flightSeconds(ELYTRA, 400, true, 0, false, false, 20), ticks / 20.0, 1e-9);
	}

	@Test
	void worn_creativeAndUnbreakableElytras() {
		assertEquals(0, flight(ELYTRA - 1, 0));
		assertEquals(0, flight(ELYTRA, 3));
		assertEquals("Flight left: 0s", ItemWear.flightLine(flight(ELYTRA - 1, 3), 3, false));
		assertEquals(1, flight(ELYTRA - 2, 0));
		double creative = ItemWear.flightSeconds(ELYTRA, 0, true, 3, false, true, 20);
		assertEquals("Flight left: ∞ (Creative)", ItemWear.flightLine(creative, 3, true));
		double unbreakable = ItemWear.flightSeconds(ELYTRA, 0, false, 0, false, false, 20);
		assertEquals("Flight left: ∞", ItemWear.flightLine(unbreakable, 0, false));
	}

	@Test
	void mendingDoesNotChangeTheEstimate() {
		// Mending only repairs from experience orbs; the wear (and so the estimate) is the elytra's damage and Unbreaking alone.
		assertEquals(flight(100, 3), flight(100, 3));
		assertEquals(331 * 4, flight(100, 3), 1e-9);
	}

	@Test
	void aSlowerServerTickRateMakesFlightLastLongerInRealTime() {
		assertEquals(862, ItemWear.flightSeconds(ELYTRA, 0, true, 0, false, false, 10), 1e-9);
		assertEquals(431, ItemWear.flightSeconds(ELYTRA, 0, true, 0, false, false, 0), 1e-9, "a broken tick rate is 20");
	}

	@Test
	void longFlightsShowHours() {
		// A datapack's Unbreaking X: 431 * 11 s.
		assertEquals("~1h 19m", ItemWear.flightText(flight(0, 10), true));
		assertEquals("59s", ItemWear.flightText(59.9, false));
	}
}

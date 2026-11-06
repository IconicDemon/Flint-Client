package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** AppleSkin's estimate of the health natural regeneration gives back, and what eating adds to it. */
class FoodRegenTest {
	@Test
	void belowEighteenNothingRegenerates() {
		assertEquals(0, FoodRegen.estimate(17, 5, 0));
		assertEquals(0, FoodRegen.afterEating(12, 0, 0, 2, 0.4F), "a cookie at 12 still leaves you under 18");
	}

	@Test
	void eighteenWithoutSaturationHealsOnePointBeforeDropping() {
		// One slow regeneration step (6 exhaustion) takes the bar to 17.
		assertEquals(1, FoodRegen.estimate(18, 0, 0));
	}

	@Test
	void aFullBarWithSaturationHealsFromIt() {
		float healed = FoodRegen.estimate(20, 5, 0);
		assertTrue(healed > 2 && healed < 10, "5 saturation heals a few points: " + healed);
	}

	@Test
	void steakWhileHungryGivesHealthBack() {
		// 12 food, no saturation; steak (8 hunger, 12.8 saturation) brings it to 20 with 12.8 saturation.
		float gain = FoodRegen.afterEating(12, 0, 0, 8, 12.8F);
		assertTrue(gain > 10, "steak at 12 food heals many points: " + gain);
	}

	@Test
	void broken_valuesGiveNothing() {
		assertEquals(0, FoodRegen.estimate(20, Float.NaN, 0));
		assertEquals(0, FoodRegen.estimate(20, 5, Float.POSITIVE_INFINITY));
	}
}

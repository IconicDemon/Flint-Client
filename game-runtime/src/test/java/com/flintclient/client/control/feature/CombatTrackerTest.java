package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reach, combos and kills come only from what the server confirms. */
class CombatTrackerTest {
	private final CombatTracker t = new CombatTracker();

	@Test
	void aSwingShowsItsDistanceOnlyOnceTheHitLands() {
		t.swing(7, 2.94, 1000);
		assertTrue(Double.isNaN(t.reach(1000, 3000)), "a swing alone proves nothing");
		t.landed(7, true, 1100);
		assertEquals(2.94, t.reach(1200, 3000));
		assertTrue(Double.isNaN(t.reach(5000, 3000)), "old news after a while");
		t.swing(8, 1.5, 6000);
		t.landed(8, false, 6000 + CombatTracker.CONFIRM_MS + 1);
		assertTrue(Double.isNaN(t.reach(7200, 3000)), "too late to belong to that swing");
	}

	@Test
	void combosCountLandedHitsAndEndOnTimeoutOrWhenHurt() {
		t.landed(1, true, 0);
		t.landed(1, true, 500);
		t.landed(2, true, 900);
		assertEquals(3, t.combo(1000));
		assertEquals(0, t.combo(900 + CombatTracker.COMBO_TIMEOUT_MS + 1));
		t.landed(1, true, 5000);
		assertEquals(1, t.combo(5000), "a new combo after the timeout");
		t.hurt(30, 5100);
		assertEquals(0, t.combo(5200), "taking damage ends it");
		assertEquals(30, t.hurtYaw());
	}

	@Test
	void aDeathIsYourKillOnlyIfYouHitThatEntityLately() {
		assertFalse(t.died(4, 100), "never hit");
		t.landed(4, true, 1000);
		assertFalse(t.died(5, 1200), "someone else");
		assertTrue(t.died(4, 1000 + CombatTracker.KILL_WINDOW_MS));
		assertTrue(t.killWasPlayer());
		assertEquals(1, t.kills());
		assertFalse(t.died(4, 1000 + CombatTracker.KILL_WINDOW_MS + 10), "counted once");
		t.landed(9, false, 10_000);
		assertFalse(t.died(9, 10_000 + CombatTracker.KILL_WINDOW_MS + 1), "too long ago");
	}

	@Test
	void resetForgetsTheLastWorld() {
		t.swing(1, 3, 0);
		t.landed(1, true, 10);
		t.reset();
		assertEquals(0, t.combo(20));
		assertTrue(Double.isNaN(t.reach(20, 3000)));
		assertFalse(t.died(1, 30));
	}
}

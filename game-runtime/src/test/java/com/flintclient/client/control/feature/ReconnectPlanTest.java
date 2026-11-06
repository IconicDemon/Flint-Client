package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reconnect's tries: the delay, growing by the increment up to the max, and a limited count that a join resets. */
class ReconnectPlanTest {
	@Test
	void aFixedDelayForEachTryUntilTheyAreSpent() {
		ReconnectPlan plan = new ReconnectPlan();
		for (int i = 0; i < 3; i++) {
			assertEquals(5, plan.nextDelay(3, 5, false, 5, 20));
			plan.tried();
		}
		assertEquals(-1, plan.nextDelay(3, 5, false, 5, 20), "three tries made");
		plan.reset();
		assertEquals(5, plan.nextDelay(3, 5, false, 5, 20), "joined again: from the first");
	}

	@Test
	void anIncrementingDelayStopsAtTheMax() {
		ReconnectPlan plan = new ReconnectPlan();
		double[] expected = {5, 10, 15, 20, 20};
		for (double e : expected) {
			assertEquals(e, plan.nextDelay(10, 5, true, 5, 20));
			plan.tried();
		}
		plan.reset();
		assertEquals(30, plan.nextDelay(10, 30, true, 5, 20), "a max below the delay never shortens it");
	}
}

package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FpsMeterTest {
	@Test
	void theAverageCoversTheLastTwoRefreshes() {
		long[] history = new long[FpsMeter.LOW_REFRESHES + 1];
		// The baseline (refresh 0) saw 10 frames already recorded by someone else.
		history[0] = 10;
		assertEquals(0, FpsMeter.framesSince(10, history, 1, FpsMeter.AVERAGE_REFRESHES), "nothing new since the baseline");
		assertEquals(30, FpsMeter.framesSince(40, history, 1, FpsMeter.AVERAGE_REFRESHES), "only the frames since the baseline count");
		history[1] = 40;
		history[2] = 75;
		assertEquals(60, FpsMeter.framesSince(100, history, 3, FpsMeter.AVERAGE_REFRESHES), "frames since refresh 1, half a second ago");
	}

	@Test
	void theLowWindowCoversTwentyRefreshesAndTheRingWraps() {
		long[] history = new long[FpsMeter.LOW_REFRESHES + 1];
		int done = 0;
		long count = 0;
		for (; done < 50; done++) {
			history[done % history.length] = count;
			count += 60;
		}
		// 50 refreshes made, 60 frames apart: the last twenty of them span 20 × 60 frames.
		assertEquals(20 * 60, FpsMeter.framesSince(count, history, done, FpsMeter.LOW_REFRESHES));
		assertEquals(2 * 60, FpsMeter.framesSince(count, history, done, FpsMeter.AVERAGE_REFRESHES));
	}

	@Test
	void beforeTheBaselineThereIsNothing() {
		assertEquals(0, FpsMeter.framesSince(500, new long[FpsMeter.LOW_REFRESHES + 1], 0, FpsMeter.AVERAGE_REFRESHES));
		long[] history = new long[FpsMeter.LOW_REFRESHES + 1];
		history[0] = 900;
		assertEquals(0, FpsMeter.framesSince(500, history, 1, 2), "a count reset never gives a negative window");
	}
}

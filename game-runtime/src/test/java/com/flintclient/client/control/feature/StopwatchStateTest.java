package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Start/stop, pause and reset, as the Stopwatch module's keys work them. */
class StopwatchStateTest {
	@Test
	void startStopPauseAndReset() {
		StopwatchState watch = new StopwatchState();
		assertEquals(0, watch.elapsed(100));
		watch.pause(100);
		assertFalse(watch.running(), "pause does nothing to a stopped watch");
		watch.startStop(1000);
		assertEquals(500, watch.elapsed(1500));
		watch.pause(2000);
		assertTrue(watch.paused());
		assertEquals(1000, watch.elapsed(9000), "held while paused");
		watch.pause(10_000);
		assertEquals(1500, watch.elapsed(10_500), "resumed where it was");
		watch.startStop(11_000);
		assertFalse(watch.running());
		assertEquals(2000, watch.elapsed(50_000), "stopped: the time stays");
		watch.startStop(60_000);
		assertEquals(0, watch.elapsed(60_000), "starting again starts from zero");
		watch.reset();
		assertEquals(0, watch.elapsed(70_000));
		assertFalse(watch.running());
	}
}

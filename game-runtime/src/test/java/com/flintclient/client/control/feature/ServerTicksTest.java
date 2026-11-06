package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** TPS from the server's time updates: unknown at first, the measured rate, capped at 20, reset by time going back. */
class ServerTicksTest {
	private static final long SECOND = 1_000_000_000L;

	@Test
	void measuresTheRateTheServerTicksAt() {
		ServerTicks ticks = new ServerTicks();
		assertEquals(-1, ticks.tps());
		ticks.record(1000, 0);
		assertEquals(-1, ticks.tps(), "one update says nothing yet");
		for (int i = 1; i <= 10; i++) {
			ticks.record(1000 + 10L * i, i * SECOND);
		}
		assertEquals(10.0, ticks.tps(), 1e-9, "10 ticks every second: a lagging server");
		for (int i = 11; i <= 20; i++) {
			ticks.record(1100 + 30L * (i - 10), i * SECOND);
		}
		assertEquals(20.0, ticks.tps(), 1e-9, "a server catching up (30 a second) is capped at 20");
	}

	@Test
	void aNewWorldOrTimeGoingBackStartsAgain() {
		ServerTicks ticks = new ServerTicks();
		ticks.record(500, 0);
		ticks.record(520, SECOND);
		assertEquals(20.0, ticks.tps(), 1e-9);
		ticks.record(10, 2 * SECOND);
		assertEquals(-1, ticks.tps(), "game time went back: a new world");
		ticks.reset();
		assertEquals(-1, ticks.tps());
	}
}

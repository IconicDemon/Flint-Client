package com.flintclient.client.control.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleaseLatchTest {
	private final ReleaseLatch latch = new ReleaseLatch();

	@Test
	void aKeyActsUntilTheLatchIsArmed() {
		assertFalse(latch.holding());
		latch.observe(true);
		assertFalse(latch.holding(), "watching a held key arms nothing on its own");
	}

	@Test
	void anArmedKeyIsIgnoredForAsLongAsItsHeld() {
		latch.arm();
		for (int tick = 0; tick < 100; tick++) {
			latch.observe(true);
			assertTrue(latch.holding(), "tick " + tick);
		}
		latch.observe(false);
		assertFalse(latch.holding(), "acts again once let go");
	}

	@Test
	void closingWithTheKeyAndHoldingOnDoesNotReopen() {
		// Opened with the key, let go while open, pressed again to close and held: the close re-arms it.
		latch.arm();
		latch.observe(false);
		assertFalse(latch.holding());
		latch.arm();
		latch.observe(true);
		assertTrue(latch.holding(), "the repeats after closing are ignored");
		latch.observe(false);
		latch.observe(true);
		assertFalse(latch.holding(), "a fresh press after letting go acts");
	}

	@Test
	void armingIsIdempotent() {
		latch.arm();
		latch.arm();
		latch.observe(false);
		assertFalse(latch.holding());
	}
}

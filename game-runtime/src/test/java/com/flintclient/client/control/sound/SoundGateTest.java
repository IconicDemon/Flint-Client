package com.flintclient.client.control.sound;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SoundGateTest {
	@Test
	void aSoundSoonAfterTheLastDoesNotPlay() {
		SoundGate gate = new SoundGate();
		assertTrue(gate.allow(1_000), "the first sound plays");
		assertFalse(gate.allow(1_100), "an open 100 ms after a close stays quiet");
		assertFalse(gate.allow(1_149));
		assertTrue(gate.allow(1_150), "150 ms on, it plays");
	}

	@Test
	void onlySoundsThatPlayedCount() {
		SoundGate gate = new SoundGate();
		assertTrue(gate.allow(0));
		assertFalse(gate.allow(100));
		assertTrue(gate.allow(160), "measured from the last one that played, not the one held back");
		assertFalse(gate.allow(300));
	}

	@Test
	void aClockThatWentBackDoesNotSilenceEverything() {
		SoundGate gate = new SoundGate();
		assertTrue(gate.allow(10_000));
		assertTrue(gate.allow(5_000));
	}
}

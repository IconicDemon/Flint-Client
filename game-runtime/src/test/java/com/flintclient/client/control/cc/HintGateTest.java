package com.flintclient.client.control.cc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HintGateTest {
	@Test
	void waitsForTheWorldThenShowsOnce() {
		HintGate gate = new HintGate();
		assertFalse(gate.poll(false, false, true), "title screen or loading screen");
		assertFalse(gate.settled());
		assertTrue(gate.poll(false, true, true), "the first tick in the world");
		assertTrue(gate.settled());
		assertFalse(gate.poll(false, true, true), "never twice, even before the saved flag is written");
	}

	@Test
	void neverWhenAlreadyShown() {
		HintGate gate = new HintGate();
		assertFalse(gate.poll(true, true, true));
		assertTrue(gate.settled(), "nothing more to check this session");
		assertFalse(gate.poll(false, true, true));
	}

	@Test
	void waitsWhileTheKeyIsUnbound() {
		HintGate gate = new HintGate();
		assertFalse(gate.poll(false, true, false), "\"Press Not Bound\" would help no one");
		assertFalse(gate.settled(), "still due once a key is bound");
		assertTrue(gate.poll(false, true, true));
	}
}

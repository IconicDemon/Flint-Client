package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeedTrackerTest {
	@Test
	void nothingUntilTwoSamples() {
		SpeedTracker t = new SpeedTracker();
		assertTrue(Double.isNaN(t.speed(true, false)));
		t.sample(0, 64, 0);
		assertFalse(t.known());
		t.sample(0.2806, 64, 0);
		assertEquals(5.612, t.speed(true, false), 1e-9, "sprinting: 0.2806 blocks a tick is 5.6 b/s");
	}

	@Test
	void groundOnlyIgnoresFalling() {
		SpeedTracker t = new SpeedTracker();
		t.sample(0, 100, 0);
		t.sample(0.3, 99.6, 0.4);
		assertEquals(10.0, t.speed(true, false), 1e-9);
		assertEquals(Math.sqrt(0.25 + 0.16) * 20, t.speed(false, false), 1e-9);
	}

	@Test
	void averageCoversHalfASecond() {
		SpeedTracker t = new SpeedTracker();
		t.sample(0, 0, 0);
		double x = 0;
		for (int i = 0; i < 20; i++) {
			x += i < 10 ? 0.0 : 0.5;
			t.sample(x, 0, 0);
		}
		assertEquals(10.0, t.speed(true, true), 1e-9, "only the last 10 ticks count");
		t.sample(x, 0, 0);
		assertEquals(9.0, t.speed(true, true), 1e-9);
		assertEquals(0.0, t.speed(true, false), 1e-9);
	}

	@Test
	void teleportsRestartTheHistory() {
		SpeedTracker t = new SpeedTracker();
		t.sample(0, 0, 0);
		t.sample(0.5, 0, 0);
		t.sample(500, 0, 0);
		assertFalse(t.known());
		t.sample(500.25, 0, 0);
		assertEquals(5.0, t.speed(true, true), 1e-9);
	}

	@Test
	void resetForgetsThePosition() {
		SpeedTracker t = new SpeedTracker();
		t.sample(0, 0, 0);
		t.sample(1, 0, 0);
		t.reset();
		t.sample(5, 0, 0);
		assertFalse(t.known());
	}
}

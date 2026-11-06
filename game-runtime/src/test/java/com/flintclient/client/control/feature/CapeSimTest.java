package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CapeSimTest {
	private static final CapeSim.Settings HANGING = new CapeSim.Settings(25, 0, 1, 0);
	private static final int TIP = CapeSim.POINTS - 1;

	/** The tip's angle from straight down, towards behind, in degrees. */
	private static double lift(CapeSim sim) {
		return Math.toDegrees(Math.atan2(sim.away(TIP, 1), sim.down(TIP, 1)));
	}

	@Test
	void atRestItHangsStraightDown() {
		for (int kind : new int[] {CapeSim.FLAT, CapeSim.THREE_D, CapeSim.DUNGEONS}) {
			CapeSim sim = new CapeSim(kind);
			for (int t = 0; t < 60; t++) {
				sim.step(0, 0, 0, false, HANGING);
			}
			assertEquals(TIP, sim.down(TIP, 1), 0.5, "kind " + kind);
			assertTrue(Math.abs(lift(sim)) < 5, "kind " + kind + ": " + lift(sim));
		}
	}

	@Test
	void runningStreamsItOutBehind() {
		CapeSim sim = new CapeSim(CapeSim.THREE_D);
		for (int t = 0; t < 40; t++) {
			// Running: the cloak lags behind the body by about a block's tenth each tick, in pixels.
			sim.step(1.6F, 0, 0, false, HANGING);
		}
		double run = lift(sim);
		assertTrue(run > 20, "lifted behind: " + run);
		for (int t = 0; t < 80; t++) {
			sim.step(0, 0, 0, false, HANGING);
		}
		assertTrue(lift(sim) < run, "falls back when stopped");
	}

	@Test
	void neverInFrontOfTheBack() {
		CapeSim sim = new CapeSim(CapeSim.THREE_D);
		for (int t = 0; t < 40; t++) {
			sim.step(-2, 0, 0.5F, false, HANGING);
			for (int i = 0; i < CapeSim.POINTS; i++) {
				assertTrue(sim.away(i, 1) >= 0);
			}
		}
	}

	@Test
	void flatOneNeverSwingsSideways() {
		CapeSim sim = new CapeSim(CapeSim.FLAT);
		for (int t = 0; t < 40; t++) {
			sim.step(0.5F, 0, 3, false, HANGING);
		}
		assertEquals(0, sim.side(TIP, 1), 1e-4);
	}
}

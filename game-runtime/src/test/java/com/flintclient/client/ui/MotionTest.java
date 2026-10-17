package com.flintclient.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MotionTest {
	@Test
	void easeOutStartsAtZeroEndsAtOneAndNeverOvershoots() {
		assertEquals(0.0F, Motion.easeOut(0));
		assertEquals(1.0F, Motion.easeOut(1));
		assertEquals(1.0F, Motion.easeOut(5), "clamped after the end");
		float previous = 0;
		for (int i = 1; i <= 20; i++) {
			float v = Motion.easeOut(i / 20.0F);
			assertTrue(v >= previous && v <= 1.0F);
			previous = v;
		}
	}
}

package com.flintclient.client.control.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlurMathTest {
	private static final long FRAME_60 = 16_666_667L;
	private static final long FRAME_240 = 4_166_667L;

	@Test
	void strengthIsClamped() {
		assertEquals(0f, BlurMath.clampStrength(-1f));
		assertEquals(0f, BlurMath.clampStrength(Float.NaN));
		assertEquals(0.4f, BlurMath.clampStrength(0.4f));
		assertEquals(1f, BlurMath.clampStrength(7f));
	}

	@Test
	void noStrengthMeansNoBlur() {
		assertEquals(0f, BlurMath.retention(0f, FRAME_60));
		assertEquals(0f, BlurMath.retention(Float.NaN, FRAME_60));
	}

	@Test
	void trailLastsTheSameTimeAtAnyFrameRate() {
		// What is left of an image after 1/60 s: one 60 FPS frame, or four 240 FPS frames.
		float at60 = BlurMath.retention(0.5f, FRAME_60);
		float at240 = BlurMath.retention(0.5f, FRAME_240);
		assertEquals(at60, Math.pow(at240, 4), 1e-4);
		assertTrue(at240 > at60);
	}

	@Test
	void strongerKeepsMore() {
		assertTrue(BlurMath.retention(1f, FRAME_60) > BlurMath.retention(0.5f, FRAME_60));
		assertTrue(BlurMath.retention(0.5f, FRAME_60) > BlurMath.retention(0.1f, FRAME_60));
	}

	@Test
	void retentionIsCapped() {
		assertEquals(BlurMath.MAX_RETENTION, BlurMath.retention(1f, 0));
		assertEquals(BlurMath.MAX_RETENTION, BlurMath.retention(1f, -5));
		assertTrue(BlurMath.retention(1f, 1_000_000L) <= BlurMath.MAX_RETENTION);
	}

	@Test
	void slowFramesBarelyBlur() {
		assertTrue(BlurMath.retention(1f, 200_000_000L) < 0.2f);
	}

	@Test
	void longPausesRestartTheTrail() {
		assertTrue(BlurMath.continues(FRAME_60));
		assertTrue(BlurMath.continues(BlurMath.MAX_GAP_NANOS));
		assertFalse(BlurMath.continues(BlurMath.MAX_GAP_NANOS + 1));
		assertFalse(BlurMath.continues(-1));
	}
}

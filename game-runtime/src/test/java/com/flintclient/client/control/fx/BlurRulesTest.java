package com.flintclient.client.control.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Smooth's trail fades by time (the same at 60 and 240 FPS), Natural yields to shader packs, and the scene resets. */
class BlurRulesTest {
	@Test
	void theDefaultIsAfkzsDefaultLookAt144Fps() {
		assertEquals(0.675F, BlurRules.smoothKept(BlurRules.DEFAULT_STRENGTH, 1 / 144F), 1e-4);
		assertEquals(0.0177, BlurRules.trailSeconds(50), 1e-4);
		assertEquals(0.06, BlurRules.trailSeconds(100), 1e-9);
		assertEquals(0, BlurRules.trailSeconds(0), 1e-9);
		assertTrue(BlurRules.trailSeconds(10) < BlurRules.trailSeconds(30));
		assertTrue(BlurRules.trailSeconds(30) < BlurRules.trailSeconds(50));
		assertTrue(BlurRules.smoothKept(50, 1 / 60F) < BlurRules.smoothKept(50, 1 / 240F), "a short frame keeps more of the trail");
	}

	@Test
	void theTrailIsAsLongAt60And240Fps() {
		for (int percent : new int[] {5, 25, 50, 75, 100}) {
			// What is left of a frame a tenth of a second later.
			double at60 = Math.pow(BlurRules.smoothKept(percent, 1 / 60F), 6);
			double at240 = Math.pow(BlurRules.smoothKept(percent, 1 / 240F), 24);
			assertEquals(at60, at240, 1e-4, percent + "%");
		}
		assertTrue(BlurRules.smoothKept(50, 1 / 240F) > BlurRules.smoothKept(50, 1 / 60F), "more frames, each keeping more");
	}

	@Test
	void aHitchShowsMostlyItselfAndNothingRunsAway() {
		assertTrue(BlurRules.smoothKept(50, 0.25F) < 0.01F);
		assertTrue(BlurRules.smoothKept(100, 0.25F) < 0.02F);
		assertEquals(BlurRules.smoothKept(100, 0.25F), BlurRules.smoothKept(100, 5F), 1e-9, "a stall counts as a quarter second");
		assertTrue(BlurRules.smoothKept(100, 1 / 2000F) <= (float) BlurRules.MAX_KEPT);
		assertEquals(0, BlurRules.smoothKept(0, 1 / 60F));
		assertEquals(BlurRules.smoothKept(50, 1 / 60F), BlurRules.smoothKept(50, 0), 1e-6, "an unknown frame time counts as a sixtieth");
		assertEquals(BlurRules.smoothKept(50, 1 / 60F), BlurRules.smoothKept(50, Float.NaN), 1e-6);
	}

	@Test
	void naturalIsNaturalMotionBlursStrengthAndYieldsToShaderPacks() {
		assertEquals(1F, BlurRules.naturalStrength(50));
		assertEquals(2F, BlurRules.naturalStrength(100));
		assertEquals(BlurRules.NATURAL, BlurRules.effectiveMode(BlurRules.NATURAL, false));
		assertEquals(BlurRules.SMOOTH, BlurRules.effectiveMode(BlurRules.NATURAL, true));
		assertEquals(BlurRules.SMOOTH, BlurRules.effectiveMode(BlurRules.SMOOTH, true));
		assertEquals(BlurRules.SMOOTH, BlurRules.effectiveMode(7, false), "an unknown mode is Smooth");
	}

	@Test
	void anyChangeOfSceneStartsOver() {
		BlurRules.Scene scene = new BlurRules.Scene();
		int flags = BlurRules.flags(false, false, true, false, false);
		assertFalse(scene.same(1, 2, 1280, 720, 0, 0, flags), "the first frame");
		assertTrue(scene.same(1, 2, 1280, 720, 0, 0, flags));
		assertFalse(scene.same(9, 2, 1280, 720, 0, 0, flags), "a new world or dimension");
		assertTrue(scene.same(9, 2, 1280, 720, 0, 0, flags));
		assertFalse(scene.same(9, 3, 1280, 720, 0, 0, flags), "a respawn");
		assertFalse(scene.same(9, 3, 1920, 1080, 0, 0, flags), "a resize");
		assertFalse(scene.same(9, 3, 1920, 1080, 1, 0, flags), "F5");
		assertFalse(scene.same(9, 3, 1920, 1080, 1, 1, flags), "another mode");
		for (int flag : new int[] {BlurRules.flags(true, false, true, false, false), BlurRules.flags(false, true, true, false, false),
			BlurRules.flags(false, false, false, false, false), BlurRules.flags(false, false, true, true, false),
			BlurRules.flags(false, false, true, false, true)}) {
			scene.same(9, 3, 1920, 1080, 1, 1, flags);
			assertFalse(scene.same(9, 3, 1920, 1080, 1, 1, flag), "a screen, a pause, focus, F1 or a shader pack: " + flag);
			assertFalse(scene.same(9, 3, 1920, 1080, 1, 1, flags), "and back");
		}
		scene.reset();
		assertFalse(scene.same(9, 3, 1920, 1080, 1, 1, flags), "after a reset");
	}
}

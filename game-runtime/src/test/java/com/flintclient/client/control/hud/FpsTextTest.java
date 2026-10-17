package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The order of the FPS counter's parts, as the brief writes them. */
class FpsTextTest {
	@Test
	void reversedPutsTheLabelFirstInBothModes() {
		assertEquals("80 FPS", FpsText.compose("80", true, false, false));
		assertEquals("FPS 80", FpsText.compose("80", true, true, false));
		assertEquals("[80 FPS]", FpsText.compose("80", true, false, true));
		assertEquals("[FPS 80]", FpsText.compose("80", true, true, true));
	}

	@Test
	void theOrderHoldsWhateverTheNumber() {
		assertEquals("144 FPS", FpsText.compose("144", true, false, false));
		assertEquals("FPS 1200", FpsText.compose("1200", true, true, false));
		assertEquals("[— FPS]", FpsText.compose("—", true, false, true), "unknown: a dash, never a made-up number");
	}

	@Test
	void withoutTheLabelReversedChangesNothing() {
		assertEquals("80", FpsText.compose("80", false, false, false));
		assertEquals("80", FpsText.compose("80", false, true, false));
		assertEquals("[80]", FpsText.compose("80", false, true, true));
		assertFalse(FpsText.labelFirst(false, true));
		assertFalse(FpsText.labelAfter(false, false));
		assertTrue(FpsText.labelFirst(true, true));
		assertTrue(FpsText.labelAfter(true, false));
	}
}

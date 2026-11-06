package com.flintclient.client.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Right Shift customization is on (owner request, 2026-10-18); switching it off again is a deliberate change here. */
class BetaFeaturesTest {
	@Test
	void theRightShiftCustomizationIsOn() {
		assertTrue(BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION);
	}
}

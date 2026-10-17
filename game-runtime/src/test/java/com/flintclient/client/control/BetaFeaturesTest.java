package com.flintclient.client.control;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The first public beta ships without the Right Shift customization; turning it back on is a deliberate change here. */
class BetaFeaturesTest {
	@Test
	void theRightShiftCustomizationIsOffForTheBeta() {
		assertFalse(BetaFeatures.RIGHT_SHIFT_CUSTOMIZATION);
	}
}

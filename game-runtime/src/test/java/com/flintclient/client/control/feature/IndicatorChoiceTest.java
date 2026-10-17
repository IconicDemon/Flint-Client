package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IndicatorChoiceTest {
	private static float[] sources() {
		float[] s = new float[IndicatorChoice.SOURCES];
		Arrays.fill(s, Float.NaN);
		return s;
	}

	@Test
	void whatYoureDoingComesBeforeTheAttack() {
		float[] s = sources();
		s[IndicatorChoice.ATTACK] = 0.4F;
		assertEquals(IndicatorChoice.ATTACK, IndicatorChoice.choose(s, false));
		s[IndicatorChoice.BOW] = 0.2F;
		assertEquals(IndicatorChoice.BOW, IndicatorChoice.choose(s, false));
		s[IndicatorChoice.SLEEP] = 0.9F;
		assertEquals(IndicatorChoice.SLEEP, IndicatorChoice.choose(s, false));
	}

	@Test
	void aFullAttackHidesUnlessAlwaysShown() {
		float[] s = sources();
		s[IndicatorChoice.ATTACK] = 1F;
		assertEquals(IndicatorChoice.NONE, IndicatorChoice.choose(s, false));
		assertEquals(IndicatorChoice.ATTACK, IndicatorChoice.choose(s, true));
		assertEquals(IndicatorChoice.NONE, IndicatorChoice.choose(sources(), true), "nothing applies");
	}

	@Test
	void dynamicColorAndChargeEdge() {
		assertEquals(0xFF0030, IndicatorChoice.dynamicColor(0));
		assertEquals(0x00FF30, IndicatorChoice.dynamicColor(1));
		assertEquals(0xFFFF30, IndicatorChoice.dynamicColor(0.5F));
		assertTrue(IndicatorChoice.justCharged(0.9F, 1F));
		assertFalse(IndicatorChoice.justCharged(1F, 1F));
	}
}

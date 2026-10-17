package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LightRulesTest {
	@Test
	void levelByType() {
		assertEquals(3, LightRules.level(3, 15, 11, LightRules.BLOCK, true));
		assertEquals(4, LightRules.level(3, 15, 11, LightRules.SKY, true), "night darkens the sky by 11");
		assertEquals(15, LightRules.level(3, 15, 11, LightRules.SKY, false));
		assertEquals(4, LightRules.level(3, 15, 11, LightRules.BOTH, true));
		assertEquals(0, LightRules.level(0, 2, 11, LightRules.BOTH, true), "never below 0");
	}

	@Test
	void darkIsAtOrBelowTheDangerLevel() {
		assertTrue(LightRules.dark(0, 0));
		assertFalse(LightRules.dark(1, 0));
		assertTrue(LightRules.dark(7, 7));
	}

	@Test
	void colors() {
		int red = 0xFF0000;
		int green = 0x00FF00;
		assertEquals(red, LightRules.color(0, 0, 15, true, red, green));
		assertEquals(green, LightRules.color(15, 0, 15, true, red, green));
		int mid = LightRules.color(8, 0, 16, true, red, green);
		assertEquals(0x808000, mid, Integer.toHexString(mid));
		assertEquals(green, LightRules.color(1, 0, 15, false, red, green), "static: anything above danger is safe");
		assertEquals(green, LightRules.color(9, 9 - 1, 8, true, red, green), "a safest level at or below danger can't divide by zero");
	}
}

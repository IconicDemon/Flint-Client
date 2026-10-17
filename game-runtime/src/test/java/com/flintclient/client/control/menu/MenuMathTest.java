package com.flintclient.client.control.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MenuMathTest {
	@Test
	void typedSliderValuesSnapToStepsInsideTheRange() {
		assertEquals(160, MenuMath.snap(163, 50, 500, 10));
		assertEquals(170, MenuMath.snap(165, 50, 500, 10), "halfway rounds up");
		assertEquals(500, MenuMath.snap(9999, 50, 500, 10), "too large: the top");
		assertEquals(50, MenuMath.snap(-3, 50, 500, 10), "too small: the bottom");
		assertEquals(7, MenuMath.snap(7, 0, 100, 1));
		assertEquals(40, MenuMath.snap(40, 40, 160, 10));
	}

	@Test
	void decimalsFollowTheStep() {
		assertEquals(0, MenuMath.decimals(1));
		assertEquals(1, MenuMath.decimals(0.5F));
		assertEquals(2, MenuMath.decimals(0.05F));
		assertEquals(2, MenuMath.decimals(0.25F));
		assertEquals(3, MenuMath.decimals(0.001F));
	}

	@Test
	void typedNumbersAreReadForgivingly() {
		assertEquals(12, MenuMath.parseWhole(" 12 "));
		assertEquals(4, MenuMath.parseWhole("3.5"));
		assertEquals(-2, MenuMath.parseWhole("-2"));
		assertEquals(0.25, MenuMath.parseNumber("0,25"));
		assertNull(MenuMath.parseWhole(""));
		assertNull(MenuMath.parseWhole("abc"));
		assertNull(MenuMath.parseNumber("NaN"));
		assertNull(MenuMath.parseNumber("Infinity"));
		assertNull(MenuMath.parseNumber("-"));
	}
}

package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiScaleTest {
	/** The Control Center's minimum layout. */
	private static final int MIN_W = 480;
	private static final int MIN_H = 300;

	@Test
	void fitIsTheLargestScaleWhoseMinimumLayoutFits() {
		assertEquals(4, UiScale.fit(1920, 1200, MIN_W, MIN_H));
		assertEquals(3, UiScale.fit(1920, 1080, MIN_W, MIN_H), "1080 / 300 = 3.6");
		assertEquals(1, UiScale.fit(854, 480, MIN_W, MIN_H));
		assertEquals(0, UiScale.fit(320, 240, MIN_W, MIN_H), "not even scale 1 fits");
	}

	@Test
	void automaticComesFromTheWindowNotTheGuiScale() {
		assertEquals(3, UiScale.automatic(1920, 1080), "1080p: 640 × 360 units");
		assertEquals(4, UiScale.automatic(2560, 1440), "1440p");
		assertEquals(4, UiScale.automatic(2560, 1600), "2560 × 1600: 640 × 400 units");
		assertEquals(2, UiScale.automatic(1280, 720));
		assertEquals(1, UiScale.automatic(800, 500));
		assertEquals(8, UiScale.automatic(9000, 9000), "at most 8");
	}

	@Test
	void theScreenIsRoughlySixHundredToTwelveHundredUnitsWide() {
		int[][] windows = {{1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}, {2560, 1600}, {3200, 1800}, {3840, 2160}, {2048, 1280}};
		for (int[] window : windows) {
			int scale = UiScale.compute(0, window[0], window[1], MIN_W, MIN_H);
			float width = (float) window[0] / scale;
			assertTrue(width >= 600 && width <= 1200, window[0] + "×" + window[1] + " gives " + width + " units");
		}
	}

	@Test
	void smallerAndLargerStepFromTheAutomaticScale() {
		assertEquals(2, UiScale.compute(-1, 1920, 1080, MIN_W, MIN_H));
		assertEquals(3, UiScale.compute(0, 1920, 1080, MIN_W, MIN_H));
		assertEquals(3, UiScale.compute(1, 1920, 1080, MIN_W, MIN_H), "4 would leave 480 × 270 units, under 300 tall");
		assertEquals(8, UiScale.compute(1, 5120, 3200, MIN_W, MIN_H), "at most 8");
		assertEquals(1, UiScale.compute(-1, 1280, 720, MIN_W, MIN_H));
		assertEquals(1, UiScale.compute(-1, 1000, 700, MIN_W, MIN_H), "never below 1");
	}

	@Test
	void largerIsUsedOnlyWhenTheMinimumLayoutStillFits() {
		assertEquals(5, UiScale.compute(1, 2560, 1600, MIN_W, MIN_H), "5 leaves 512 × 320 units: it fits");
		assertEquals(2, UiScale.compute(1, 1280, 720, MIN_W, MIN_H), "3 would leave 426 units");
	}

	@Test
	void aWindowTooSmallForScaleOneStillGetsOne() {
		assertEquals(1, UiScale.compute(0, 320, 240, MIN_W, MIN_H));
		assertEquals(1, UiScale.compute(1, 320, 240, MIN_W, MIN_H));
	}
}

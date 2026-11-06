package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The colour picker's arithmetic: the square and bar's places to a colour and back. */
class HsvTest {
	@Test
	void theCornersAndTheHuesAreExact() {
		assertEquals(0xFFFFFF, Hsv.rgb(200, 0, 1), "the square's top left is white in any hue");
		assertEquals(0x000000, Hsv.rgb(200, 1, 0), "its bottom is black");
		assertEquals(0xFF0000, Hsv.rgb(0, 1, 1));
		assertEquals(0xFFFF00, Hsv.rgb(60, 1, 1));
		assertEquals(0x00FF00, Hsv.rgb(120, 1, 1));
		assertEquals(0x00FFFF, Hsv.rgb(180, 1, 1));
		assertEquals(0x0000FF, Hsv.rgb(240, 1, 1));
		assertEquals(0xFF00FF, Hsv.rgb(300, 1, 1));
		assertEquals(0xFF0000, Hsv.rgb(360, 1, 1), "the bar's bottom is red again");
		assertEquals(0xFF0000, Hsv.rgb(-360, 1, 1), "any hue wraps");
		assertEquals(0xFFFFFF, Hsv.rgb(0, -1, 2), "out-of-range places are clamped");
	}

	@Test
	void everyColourRoundTrips() {
		for (int rgb : new int[] {0xFF8139, 0x4CC27A, 0x5B8CFF, 0xA88BFF, 0x123456, 0xFEDCBA, 0x010203, 0x808080, 0xFFFFFF, 0x000000}) {
			float[] hsv = Hsv.of(rgb, 0);
			assertEquals(rgb, Hsv.rgb(hsv[0], hsv[1], hsv[2]), Integer.toHexString(rgb));
		}
		// Every 8-bit step of one channel, with the others fixed, comes back unchanged.
		for (int v = 0; v < 256; v++) {
			int rgb = 0x2000FF | v << 8;
			float[] hsv = Hsv.of(rgb, 0);
			assertEquals(rgb, Hsv.rgb(hsv[0], hsv[1], hsv[2]), Integer.toHexString(rgb));
		}
	}

	@Test
	void aGreyKeepsTheHueItWasPickedIn() {
		// Dragging the thumb to the square's left edge or bottom gives a grey or black; the bar must not jump to red.
		assertEquals(210, Hsv.of(0x808080, 210)[0]);
		assertEquals(210, Hsv.of(0x000000, 210)[0]);
		assertEquals(0, Hsv.of(0x808080, 210)[1]);
		assertEquals(0, Hsv.of(0x000000, 210)[2]);
		float[] orange = Hsv.of(0xFF8139, 210);
		assertEquals(21.8F, orange[0], 0.2F, "a colour has its own hue");
		assertEquals(1F, orange[2], 0.001F);
	}
}

package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PaletteTest {
	@Test
	void lerpHitsBothEndsAndTheMiddle() {
		assertEquals(0xFF000000, Palette.lerp(0xFF000000, 0xFFFFFFFF, 0));
		assertEquals(0xFFFFFFFF, Palette.lerp(0xFF000000, 0xFFFFFFFF, 1));
		assertEquals(0xFF808080, Palette.lerp(0xFF000000, 0xFFFFFFFF, 0.5F));
		assertEquals(0xFF000000, Palette.lerp(0xFF000000, 0xFFFFFFFF, -3), "clamped below");
	}

	@Test
	void withAlphaScalesOnlyTheAlpha() {
		assertEquals(0x80D97706, Palette.withAlpha(0xFFD97706, 0.5F), "half alpha, same colour");
		assertEquals(0x00D97706, Palette.withAlpha(0xFFD97706, 0));
	}

	@Test
	void overCompositesByTheTopAlpha() {
		assertEquals(0xFF808080, Palette.over(0x80FFFFFF, 0xFF000000), "half white over black is mid grey");
		assertEquals(0xFF123456, Palette.over(0xFF123456, 0xFF000000), "opaque stays itself");
		assertEquals(0xFF000000, Palette.over(0x00FFFFFF, 0xFF000000), "transparent shows the bottom");
	}

	@Test
	void textOnTheAccentStaysReadable() {
		// WCAG contrast of the Store label (accent ink on Flint orange) and body text on the background.
		assertEquals(true, contrast(Palette.ACCENT_INK, Palette.ACCENT) >= 4.5, "Store label contrast");
		int surface = Palette.over(Palette.SURFACE, Palette.BACKGROUND_TOP);
		assertEquals(true, contrast(Palette.TEXT, surface) >= 7, "label contrast on the glass surface");
		assertEquals(true, contrast(Palette.TEXT_DIM, surface) >= 7, "resting label contrast");
		assertEquals(true, contrast(Palette.MUTED, Palette.BACKGROUND_TOP) >= 4.5, "secondary text contrast");
	}

	private static double contrast(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(int argb) {
		double[] c = {(argb >> 16) & 255, (argb >> 8) & 255, argb & 255};
		for (int i = 0; i < 3; i++) {
			double v = c[i] / 255;
			c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
	}
}

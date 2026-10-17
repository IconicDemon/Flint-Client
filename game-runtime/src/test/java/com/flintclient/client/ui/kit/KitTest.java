package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.render.Palette;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class KitTest {
	private static String format(float value, int decimals) {
		char[] out = new char[Kit.FORMAT_CHARS];
		return new String(out, 0, Kit.format(value, decimals, out));
	}

	@Test
	void numbersAreFormattedAsWritten() {
		assertEquals("144", format(144, 0));
		assertEquals("0", format(0, 0));
		assertEquals("12.5", format(12.46F, 1));
		assertEquals("−3.0", format(-3, 1));
		assertEquals("0.0", format(-0.01F, 1), "no minus on a value that rounds to zero");
		assertEquals("1000.25", format(1000.25F, 2));
		assertEquals("7.005", format(7.005F, 3));
		assertEquals("16777216", format(16777216, 0));
	}

	@Test
	void theLongestNumbersFitTheDocumentedBuffer() {
		int longest = 0;
		float[] extremes = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1.0E18F, Float.NaN};
		for (float value : extremes) {
			for (int decimals = 0; decimals <= 3; decimals++) {
				longest = Math.max(longest, format(value, decimals).length());
			}
		}
		assertEquals(Kit.FORMAT_CHARS, longest, "the buffer size is exactly the longest result");
		assertEquals("0", format(Float.NaN, 0));
	}

	@Test
	void formattingAllocatesNothing() {
		// HotSpot counts every byte a thread allocates (TLABs included), so even one object per call would show.
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		char[] out = new char[Kit.FORMAT_CHARS];
		int sink = 0;
		for (int i = 0; i < 20_000; i++) {
			sink += Kit.format(i * 0.37F - 900, i & 3, out);
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 20_000; i++) {
			sink += Kit.format(i * 0.37F - 900, i & 3, out);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink > 0);
		assertTrue(allocated < 1024, allocated + " bytes for 20,000 numbers");
	}

	@Test
	void kitMotionFollowsTheKitsPreferences() {
		KitPrefs defaults = KitPrefs.DEFAULT;
		assertFalse(Kit.motionReduced(defaults, false));
		assertTrue(Kit.motionReduced(defaults, true), "follows Minecraft by default");
		assertTrue(Kit.motionReduced(new KitPrefs.Fixed(0, false, false, true), false), "animations off");
		assertFalse(Kit.motionReduced(new KitPrefs.Fixed(0, false, true, false), true), "not following Minecraft");
	}

	@Test
	void theDefaultPreferencesAreAutomaticAndCalm() {
		assertEquals(0, KitPrefs.DEFAULT.uiScaleDelta());
		assertFalse(KitPrefs.DEFAULT.highContrast());
		assertTrue(KitPrefs.DEFAULT.animationsEnabled());
		assertTrue(KitPrefs.DEFAULT.followMinecraftReducedMotion());
	}

	@Test
	void textOnTheDeckIsReadable() {
		int deck = Tokens2.DECK | 0xFF000000;
		assertTrue(contrast(Tokens2.TEXT, deck) >= 7, "text on the deck");
		assertTrue(contrast(Tokens2.TEXT_DIM, deck) >= 7, "dim text on the deck");
		assertTrue(contrast(Tokens2.MUTED, deck) >= 4.5, "muted text on the deck");
		assertTrue(contrast(Tokens2.EMBER_TEXT, deck) >= 4.5, "ember text on the deck");
		assertTrue(contrast(Tokens2.WARN, deck) >= 4.5, "warnings on the deck");
		assertTrue(contrast(Tokens2.DANGER, deck) >= 4.5, "a destructive text button");
		assertTrue(contrast(Tokens2.INK, Tokens2.EMBER) >= 4.5, "ink on the primary button");
	}

	@Test
	void textOnTheKitsFillsIsReadable() {
		assertTrue(contrast(Tokens.TEXT, Tokens.WINDOW | 0xFF000000) >= 7, "text on the window");
		assertTrue(contrast(Tokens.MUTED, Tokens.CARD) >= 4.5, "muted text on a card");
		assertTrue(contrast(Tokens.ACCENT_INK, Tokens.ACCENT) >= 4.5, "ink on the primary button");
		assertTrue(contrast(Tokens.DANGER_INK, Tokens.DANGER) >= 4.5, "ink on the danger button");
		assertTrue(contrast(Tokens.ACCENT_TEXT, Palette.over(Tokens.ACCENT_SOFT, Tokens.WINDOW | 0xFF000000)) >= 4.5, "a selected chip");
	}

	private static double contrast(int a, int b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(int argb) {
		return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
	}

	private static double channel(int value) {
		double c = (value & 255) / 255.0;
		return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}

package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Flint's notices always fit the screen and ease in and out. */
class NoticeStyleTest {
	@Test
	void aNoticeIsNeverWiderThanTheScreenLessItsMargins() {
		// GUI-scaled widths of 854x480 at scales 1 to 4, 1280x720, 1920x1080, 2560x1440 and 4K at auto scale, in pixels.
		for (int screen : new int[] {854, 427, 284, 213, 1280, 1920, 2560, 3840, 120, 8}) {
			for (int margin : new int[] {4, 8, 16}) {
				for (int wanted : new int[] {0, 40, 300, 2000, 100_000}) {
					int w = NoticeStyle.fitWidth(wanted, screen, margin);
					assertTrue(w >= 0, "never negative");
					assertTrue(w <= wanted, "never wider than it needs");
					assertTrue(w <= Math.max(0, screen - 2 * margin), screen + " wide, " + margin + " margin: " + w);
				}
			}
		}
		assertEquals(300, NoticeStyle.fitWidth(300, 1920, 8));
		assertEquals(838, NoticeStyle.fitWidth(2000, 854, 8));
		assertEquals(0, NoticeStyle.fitWidth(50, 10, 8));
	}

	@Test
	void itEasesInAndFadesOut() {
		assertEquals(0F, NoticeStyle.alpha(0, 4000), 1e-6);
		assertTrue(NoticeStyle.alpha(100, 4000) > 0.5F, "an ease-out comes in quickly");
		assertEquals(1F, NoticeStyle.alpha(2000, 4000), 1e-6);
		assertTrue(NoticeStyle.alpha(3800, 4000) < 1F && NoticeStyle.alpha(3800, 4000) > 0F);
		assertEquals(0F, NoticeStyle.alpha(4000, 4000), 1e-6);
		assertEquals(0F, NoticeStyle.alpha(9000, 4000), 1e-6);
		for (long t = 0; t <= 4000; t += 10) {
			float a = NoticeStyle.alpha(t, 4000);
			assertTrue(a >= 0F && a <= 1F);
		}
	}

	@Test
	void fadingKeepsTheColour() {
		assertEquals(0x80FFFFFF, NoticeStyle.fade(0xFFFFFFFF, 0.5F));
		assertEquals(0x00123456, NoticeStyle.fade(0xFF123456, 0F));
		assertEquals(0xFF123456, NoticeStyle.fade(0xFF123456, 2F));
	}
}

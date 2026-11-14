package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tooltips and popovers fit the screen at every window size: their width, their lines, and where they go. */
class TipPlaceTest {
	/** Every letter 10 px wide, a space too. */
	private static int width(String s) {
		return s.length() * 10;
	}

	private static int x(long placed) {
		return TipPlace.x(placed);
	}

	private static int y(long placed) {
		return TipPlace.y(placed);
	}

	/** The tooltip at {@code placed}, {@code w × h}, is wholly on a {@code sw × sh} screen with {@code gap} margins. */
	private static void inside(long placed, int w, int h, int sw, int sh, int gap) {
		assertTrue(x(placed) >= gap && x(placed) + w <= sw - gap, "x " + x(placed) + " w " + w + " on " + sw);
		assertTrue(y(placed) >= gap && y(placed) + h <= sh - gap, "y " + y(placed) + " h " + h + " on " + sh);
	}

	@Test
	void aLongLineWrapsAtWordsToTheWidth() {
		List<String> rows = TipPlace.wrapWords("1: your face, 2: your body, 3 and more: that many blocks ahead", 200, TipPlaceTest::width);
		assertEquals(List.of("1: your face, 2:", "your body, 3 and", "more: that many", "blocks ahead"), rows);
		rows.forEach(r -> assertTrue(width(r) <= 200, r));
		assertEquals(List.of("short"), TipPlace.wrapWords("short", 200, TipPlaceTest::width), "a line that fits stays whole");
		assertEquals(List.of("a", "pneumonoultramicroscopic", "b"), TipPlace.wrapWords("a pneumonoultramicroscopic b", 100, TipPlaceTest::width),
			"a word too long for a line gets one of its own");
		assertEquals(List.of(""), TipPlace.wrapWords("", 100, TipPlaceTest::width));
	}

	@Test
	void theTextIsNeverWiderThanTheScreen() {
		assertEquals(440 - 2 * 8, TipPlace.textWidth(440, 1920, 6, 8), "its own width on a wide screen");
		assertEquals(300 - 2 * 6 - 2 * 8, TipPlace.textWidth(440, 300, 6, 8), "the screen's on a narrow one");
		assertEquals(1, TipPlace.textWidth(440, 10, 6, 8), "never nothing");
		assertEquals(10, TipPlace.rowsThatFit(720, 10, 8, 68, 4), "as many lines as fit the height");
		assertEquals(1, TipPlace.rowsThatFit(20, 10, 8, 68, 4), "at least one");
	}

	@Test
	void belowTheControlWhenItFitsAboveWhenItDoesntAndAlwaysInside() {
		int sw = 1280;
		int sh = 720;
		long below = TipPlace.place(600, 100, 80, 30, 300, 120, sw, sh, 6, false);
		assertEquals(600 + 40 - 150, x(below), "centred under it");
		assertEquals(136, y(below), "below it, gap 6");
		long above = TipPlace.place(600, 640, 80, 30, 300, 120, sw, sh, 6, false);
		assertEquals(640 - 6 - 120, y(above), "flipped above near the bottom");
		long right = TipPlace.place(1200, 100, 70, 30, 300, 120, sw, sh, 6, false);
		assertEquals(sw - 6 - 300, x(right), "pulled in from the right edge");
		long left = TipPlace.place(0, 100, 40, 30, 300, 120, sw, sh, 6, false);
		assertEquals(6, x(left), "pulled in from the left edge");
		long tall = TipPlace.place(600, 300, 80, 30, 300, 500, sw, sh, 6, false);
		inside(tall, 300, 500, sw, sh, 6);
	}

	@Test
	void besideALeftRailControlWhenItFitsElseBelow() {
		long beside = TipPlace.place(10, 300, 40, 40, 200, 60, 1280, 720, 6, true);
		assertEquals(10 + 40 + 6, x(beside));
		assertEquals(320 - 30, y(beside), "centred on it");
		long tooWide = TipPlace.place(10, 300, 40, 40, 300, 60, 320, 720, 6, true);
		assertEquals(346, y(tooWide), "no room beside it on a narrow screen: under it");
		inside(tooWide, 300, 60, 320, 720, 6);
	}

	@Test
	void everyWindowSizeKeepsItOnScreen() {
		int[][] screens = {{1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {2560, 1080}, {1024, 768}, {800, 600}};
		for (int[] s : screens) {
			for (int ax = 0; ax < s[0]; ax += s[0] / 7) {
				for (int ay = 0; ay < s[1]; ay += s[1] / 5) {
					int w = Math.min(500, s[0] - 12);
					int h = Math.min(200, s[1] - 12);
					inside(TipPlace.place(ax, ay, 60, 24, w, h, s[0], s[1], 6, ax < s[0] * 0.15F), w, h, s[0], s[1], 6);
				}
			}
		}
	}
}

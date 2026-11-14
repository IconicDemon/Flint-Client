package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Other mods' icons as white marks for Flint's menu: the tile goes, the shape stays white, small icons stay sharp. */
class WhiteMarkTest {
	private static final int BLUE = 0xFF134BFF;
	private static final int WHITE = 0xFFFFFFFF;

	/** Mod Menu 21's 16 × 16 icon: a white list on a blue square (its own pixels, '#' white). */
	private static final String[] MOD_MENU = {
		"oooooooooooooooo",
		"oooooooooooooooo",
		"oo##########o#oo",
		"oooooooooooooooo",
		"oo##o#########oo",
		"oo##o#########oo",
		"oooooooooooooooo",
		"oo##o#########oo",
		"oo##o#########oo",
		"oooooooooooooooo",
		"oo##o#########oo",
		"oo##o#########oo",
		"oooooooooooooooo",
		"oo#####oo#####oo",
		"oooooooooooooooo",
		"oooooooooooooooo",
	};

	private static Pixels pixels(String[] rows, int tile, int ink, int clear) {
		int w = rows[0].length();
		int h = rows.length;
		int[] argb = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				char c = rows[y].charAt(x);
				argb[y * w + x] = c == '#' ? ink : c == 'o' ? tile : clear;
			}
		}
		return new Pixels(w, h, argb);
	}

	@Test
	void modMenusBlueSquareBecomesAWhiteList() {
		Pixels icon = pixels(MOD_MENU, BLUE, WHITE, 0);
		assertEquals(BLUE, WhiteMark.tileColour(icon.argb(), 16, 16));
		Pixels mark = WhiteMark.of(icon);
		assertNotNull(mark);
		// Enlarged four times, pixel for pixel: 64 across.
		assertEquals(64, mark.width());
		assertEquals(64, mark.height());
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int p = mark.get(x * 4 + 1, y * 4 + 2);
				if (MOD_MENU[y].charAt(x) == '#') {
					assertEquals(WHITE, p, "the list stays, white, at " + x + "," + y);
				} else {
					assertEquals(0, p >>> 24, "the blue tile goes at " + x + "," + y);
				}
			}
		}
	}

	@Test
	void anIconOnAClearBackgroundKeepsItsShapeInWhiteAndGrey() {
		String[] rows = {
			"........",
			"..####..",
			".#oooo#.",
			".#oooo#.",
			".#oooo#.",
			".#oooo#.",
			"..####..",
			"........",
		};
		// A dark outline round a light middle, on nothing.
		Pixels mark = WhiteMark.of(pixels(rows, 0xFFF0E070, 0xFF202020, 0));
		assertNotNull(mark);
		assertEquals(0, WhiteMark.tileColour(pixels(rows, 0xFFF0E070, 0xFF202020, 0).argb(), 8, 8));
		int k = mark.width() / 8;
		int outline = mark.get(2 * k, 1 * k);
		int middle = mark.get(3 * k, 3 * k);
		assertEquals(0xFF, outline >>> 24);
		assertEquals(0xFF, middle >>> 24);
		assertEquals(0, mark.get(0, 0) >>> 24, "the clear background stays clear");
		int outlineGrey = outline & 0xFF;
		int middleGrey = middle & 0xFF;
		assertTrue(middleGrey > outlineGrey + 60, "light parts white, dark parts grey: " + middleGrey + " vs " + outlineGrey);
		assertEquals(outlineGrey, (outline >> 16) & 0xFF, "grey, no colour");
		assertTrue(outlineGrey >= 140, "dark parts stay visible on the dark tile");
	}

	@Test
	void aPictureFromEdgeToEdgeGivesNoMark() {
		// Mod Menu 17's kind of icon: blue all over, white scribbles running off the edges, black lettering.
		String[] rows = {
			"oo#ooooo#ooooooo",
			"ooo#ooooo#oooooo",
			"oooo#ooooo#ooooo",
			"ooooo#ooooo#oooo",
			"oo**oo**oo**oooo",
			"oo*o*o*ooo*o*ooo",
			"oo*o*o**oo**oooo",
			"oooooooooooooooo",
			"#oooooooooooooo#",
			"o#oo**o**o*oo*o#",
			"oo#o*o*o*o**o*oo",
			"ooo#*o*o**o*o*oo",
			"oooo#ooooooooooo",
			"ooooo#oooooooooo",
			"oooooo#ooooooooo",
			"ooooooo#oooooooo",
		};
		int[] argb = new int[16 * 16];
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				char c = rows[y].charAt(x);
				argb[y * 16 + x] = c == '#' ? WHITE : c == '*' ? 0xFF000301 : BLUE;
			}
		}
		assertEquals(0, WhiteMark.tileColour(argb, 16, 16), "not one colour round the edge");
		assertNull(WhiteMark.of(new Pixels(16, 16, argb)), "too much detail for a button: its initial instead");
	}

	@Test
	void nothingLeftMeansNoMark() {
		String[] plain = new String[8];
		java.util.Arrays.fill(plain, "oooooooo");
		assertNull(WhiteMark.of(pixels(plain, BLUE, WHITE, 0)), "a tile of one colour leaves nothing");
		String[] empty = new String[8];
		java.util.Arrays.fill(empty, "........");
		assertNull(WhiteMark.of(pixels(empty, BLUE, WHITE, 0)), "nothing to see");
	}

	@Test
	void largeIconsAreNotEnlarged() {
		Pixels big = WhiteMark.enlarged(new Pixels(128, 96, new int[128 * 96]));
		assertEquals(128, big.width());
		assertEquals(96, big.height());
		assertEquals(66, WhiteMark.enlarged(new Pixels(33, 20, new int[33 * 20])).width(), "the least whole factor to 64");
	}
}

package com.flintclient.client.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class BackdropTest {
	private static void assertCover(int w, int h, float u, float v, float vw, float vh) {
		float[] crop = Backdrop.cover(w, h);
		assertEquals(u, crop[0], 0.05, "u for " + w + "x" + h);
		assertEquals(v, crop[1], 0.05, "v for " + w + "x" + h);
		assertEquals(vw, crop[2], 0.05, "visible width for " + w + "x" + h);
		assertEquals(vh, crop[3], 0.01, "visible height for " + w + "x" + h);
	}

	@Test
	void sixteenByNineWindowsShowTheWholeArtwork() {
		assertCover(1920, 1080, 0, 0, 1600, 900);
		assertCover(3840, 2160, 0, 0, 1600, 900);
		assertCover(1280, 720, 0, 0, 1600, 900);
	}

	@Test
	void widerWindowsCropTopAndBottomEvenly() {
		// 21:9: full width, the height trimmed equally above and below.
		assertCover(2560, 1080, 0, 112.5F, 1600, 675);
	}

	@Test
	void tallerWindowsCropTheSidesEvenly() {
		// A 16:10 MacBook window and a portrait window: full height, sides trimmed.
		assertCover(2940, 1838, 80.2F, 0, 1439.6F, 900);
		assertCover(1280, 1600, 440, 0, 720, 900);
	}

	@Test
	void drawingReusesOneCropBuffer() {
		float[] into = new float[4];
		assertSame(into, Backdrop.cover(2560, 1080, into));
		assertArrayEquals(Backdrop.cover(2560, 1080), into);
		Backdrop.cover(1280, 1600, into);
		assertArrayEquals(Backdrop.cover(1280, 1600), into, "nothing left over from the last frame");
	}

	@Test
	void theArtworkIsNeverStretched() {
		for (int[] size : new int[][] {{854, 480}, {2940, 1846}, {1280, 1692}, {2940, 784}, {640, 400}}) {
			float[] crop = Backdrop.cover(size[0], size[1]);
			assertEquals(size[0] / (float) size[1], crop[2] / crop[3], 0.001, "aspect kept for " + size[0] + "x" + size[1]);
		}
	}
}

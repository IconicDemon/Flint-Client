package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** {@link MaskAtlas#centred}: a tile's lone icon moved to the square's middle by whole pixels, its coverage untouched. */
class CentredGlyphTest {
	private static final int SIZE = 10;

	private static int[] mask(int left, int top, int w, int h) {
		int[] argb = new int[SIZE * SIZE];
		for (int y = top; y < top + h; y++) {
			for (int x = left; x < left + w; x++) {
				// Soft edges stay as they are: a different coverage per pixel.
				argb[y * SIZE + x] = (40 + 10 * (x - left) + (y - top)) << 24 | 0xFFFFFF;
			}
		}
		return argb;
	}

	@Test
	void inkSitsInTheMiddleBothWays() {
		// A flag drawn high and left on its baseline: 4 × 6 at (1, 0) comes to (3, 2).
		int[] out = MaskAtlas.centred(mask(1, 0, 4, 6), SIZE);
		assertArrayEquals(mask(3, 2, 4, 6), out);
	}

	@Test
	void anOddPixelLeftOverGoesRightAndDown() {
		// 3 × 5 in 10: 7 and 5 left over, 4 right of it and 3 below.
		int[] out = MaskAtlas.centred(mask(0, 0, 3, 5), SIZE);
		assertArrayEquals(mask(4, 3, 3, 5), out);
	}

	@Test
	void alreadyCentredAndEmptyMasksAreKept() {
		int[] centred = mask(3, 2, 4, 6);
		assertSame(centred, MaskAtlas.centred(centred, SIZE));
		int[] empty = new int[SIZE * SIZE];
		assertSame(empty, MaskAtlas.centred(empty, SIZE));
		int[] full = mask(0, 0, SIZE, SIZE);
		assertEquals(full.length, MaskAtlas.centred(full, SIZE).length);
		assertSame(full, MaskAtlas.centred(full, SIZE));
	}
}

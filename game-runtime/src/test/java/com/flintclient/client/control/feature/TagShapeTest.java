package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagShapeTest {
	@Test
	void aSquareBoxIsOneRectangle() {
		List<float[]> fill = new ArrayList<>();
		List<float[]> border = new ArrayList<>();
		TagShape.build(-11, -1, 10, 9, 0, 0, fill, border);
		assertEquals(1, fill.size());
		assertArrayEquals(new float[] {-11, -1, 10, 9}, fill.get(0));
		assertTrue(border.isEmpty());
	}

	@Test
	void aBorderRingsTheBoxWithoutCoveringIt() {
		List<float[]> fill = new ArrayList<>();
		List<float[]> border = new ArrayList<>();
		TagShape.build(0, 0, 20, 10, 0, 1, fill, border);
		assertEquals(1, fill.size());
		assertEquals(4, border.size(), "top, left, right, bottom");
		assertEquals(22 * 12 - 20 * 10, area(border), 1e-3);
		for (float[] r : border) {
			assertFalse(overlaps(r, fill.get(0)));
		}
	}

	@Test
	void roundedCornersTakeAreaFromTheCornersOnly() {
		List<float[]> fill = new ArrayList<>();
		List<float[]> border = new ArrayList<>();
		TagShape.build(0, 0, 20, 10, 2, 1, fill, border);
		float area = area(fill);
		assertTrue(area < 200 && area > 200 - 4 * 4, "less than the square, never more than its corners");
		float middleWidth = 0;
		for (float[] r : fill) {
			if (r[1] <= 5 && r[3] >= 5) {
				middleWidth = r[2] - r[0];
			}
		}
		assertEquals(20, middleWidth, 1e-3, "the sides are straight between the corners");
		for (float[] a : fill) {
			for (float[] b : border) {
				assertFalse(overlaps(a, b), "nothing is drawn twice");
			}
		}
		for (int i = 0; i < fill.size(); i++) {
			for (int j = i + 1; j < fill.size(); j++) {
				assertFalse(overlaps(fill.get(i), fill.get(j)));
			}
		}
	}

	private static float area(List<float[]> rects) {
		float sum = 0;
		for (float[] r : rects) {
			sum += (r[2] - r[0]) * (r[3] - r[1]);
		}
		return sum;
	}

	private static boolean overlaps(float[] a, float[] b) {
		return Math.min(a[2], b[2]) - Math.max(a[0], b[0]) > 1e-4 && Math.min(a[3], b[3]) - Math.max(a[1], b[1]) > 1e-4;
	}
}

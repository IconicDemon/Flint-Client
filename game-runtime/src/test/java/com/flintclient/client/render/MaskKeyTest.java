package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaskKeyTest {
	private static final int[] KINDS = {MaskKey.CORNER, MaskKey.NOTCH, MaskKey.ROUND_RECT, MaskKey.RING, MaskKey.RING_CORNER, MaskKey.ICON, MaskKey.MARK};

	/** Values around every bit boundary of each field, plus sizes the menu really uses. */
	private static int[] samples(int max) {
		return new int[] {0, 1, 2, 7, 8, 255, 256, 346, 4096, max / 2, max - 1, max};
	}

	@Test
	void everyParameterComesBackOut() {
		for (int kind : KINDS) {
			for (int a : samples(MaskKey.MAX_A)) {
				for (int d : samples(MaskKey.MAX_D)) {
					long key = MaskKey.of(kind, a, MaskKey.MAX_B - a, Math.min(a, MaskKey.MAX_C), d);
					assertEquals(kind, MaskKey.kind(key));
					assertEquals(a, MaskKey.a(key));
					assertEquals(MaskKey.MAX_B - a, MaskKey.b(key));
					assertEquals(Math.min(a, MaskKey.MAX_C), MaskKey.c(key));
					assertEquals(d, MaskKey.d(key));
				}
			}
		}
	}

	@Test
	void differentShapesNeverShareAKey() {
		Set<Long> keys = new HashSet<>();
		int count = 0;
		for (int kind : KINDS) {
			for (int a : samples(MaskKey.MAX_A)) {
				for (int b : samples(MaskKey.MAX_B)) {
					for (int c : samples(MaskKey.MAX_C)) {
						for (int d : samples(MaskKey.MAX_D)) {
							keys.add(MaskKey.of(kind, a, b, c, d));
							count++;
						}
					}
				}
			}
		}
		assertEquals(count, keys.size());
	}

	@Test
	void theMenusShapesFitTheRanges() {
		// Rounded rectangles and rings are atlas quads only up to 120,000 px, so their sides and radii are far inside.
		long biggestQuad = MaskKey.of(MaskKey.RING, 32_000, 3, 1, 1);
		assertEquals(32_000, MaskKey.a(biggestQuad));
		assertTrue(MaskKey.MAX_A >= 16_384 && MaskKey.MAX_B >= 16_384, "a side as wide as the largest window");
		assertTrue(MaskKey.MAX_C >= 8_192 && MaskKey.MAX_D >= 8_192, "a corner radius or border up to half that");
		assertTrue(Icon.values().length - 1 <= MaskKey.MAX_B, "every icon");
	}

	@Test
	void valuesOutsideTheRangesAreRejectedInsteadOfColliding() {
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(MaskKey.CORNER, MaskKey.MAX_A + 1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(MaskKey.RING, 10, MaskKey.MAX_B + 1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(MaskKey.RING, 10, 10, MaskKey.MAX_C + 1, 0));
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(MaskKey.RING, 10, 10, 2, MaskKey.MAX_D + 1));
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(MaskKey.CORNER, -1, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> MaskKey.of(7, 1, 0, 0, 0));
	}
}

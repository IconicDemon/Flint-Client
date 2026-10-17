package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TooltipScrollTest {
	private static final long T = 1_000_000_000L;

	/** A 400-unit tooltip in a 240-unit window, placed as Minecraft does (bottom at the window's edge minus 3). */
	private static TooltipScroll tall(boolean snap) {
		TooltipScroll s = new TooltipScroll();
		s.begin(100, 400, 40, T, snap, 1);
		s.place(50, 240 - 403, 100, 400, 320, 240, false, T, false);
		return s;
	}

	@Test
	void aTallTooltipOpensAtItsTop() {
		TooltipScroll s = tall(true);
		assertEquals(4 - (240 - 403), s.offsetY(), "top edge 4 units from the window's top");
		assertTrue(s.overflows(true));
		assertFalse(s.overflows(false));
	}

	@Test
	void withoutSnapItStaysWhereMinecraftPutIt() {
		assertEquals(0, tall(false).offsetY());
	}

	@Test
	void scrollingStopsAtTheOtherEdge() {
		TooltipScroll s = tall(true);
		for (int i = 0; i < 100; i++) {
			s.scroll(true, -1, false);
		}
		s.place(50, 240 - 403, 100, 400, 320, 240, false, T, false);
		int y = 240 - 403 + s.offsetY();
		assertEquals(240 - 4, y + 400, "bottom edge 4 units from the window's bottom");
		assertFalse(s.scroll(false, 1, false), "it fits sideways, so it doesn't move sideways");
	}

	@Test
	void aFittingTooltipOnlyMovesInFreeMode() {
		TooltipScroll s = new TooltipScroll();
		s.begin(100, 50, 5, T, true, 1);
		s.place(60, 60, 100, 50, 320, 240, false, T, false);
		assertFalse(s.scroll(true, 1, false));
		s.place(60, 60, 100, 50, 320, 240, true, T, false);
		assertTrue(s.scroll(true, -3, false));
		s.place(60, 60, 100, 50, 320, 240, true, T, false);
		assertEquals(-30, s.offsetY());
		for (int i = 0; i < 100; i++) {
			s.scroll(true, -1, false);
		}
		s.place(60, 60, 100, 50, 320, 240, true, T, false);
		assertEquals(16, 60 + s.offsetY() + 50, "16 units stay on screen");
	}

	@Test
	void lineShiftMovesWholeLines() {
		TooltipScroll s = tall(false);
		// Minecraft shows its bottom; the wheel up reveals what's above.
		s.scroll(true, 0.3, true);
		s.place(50, 240 - 403, 100, 400, 320, 240, false, T, false);
		assertEquals(TooltipScroll.LINE, s.offsetY(), 0.001);
	}

	@Test
	void smoothScrollingGlides() {
		TooltipScroll s = tall(false);
		s.scroll(true, 2, false);
		s.place(50, 240 - 403, 100, 400, 320, 240, false, T + 16_000_000L, true);
		assertTrue(s.offsetY() > 0 && s.offsetY() < 20, "part of the way after one frame: " + s.offsetY());
		s.place(50, 240 - 403, 100, 400, 320, 240, false, T + 216_000_000L, true);
		assertEquals(20, s.offsetY());
	}

	@Test
	void scaleIsClampedAndResetForANewTooltip() {
		TooltipScroll s = tall(true);
		for (int i = 0; i < 50; i++) {
			s.zoom(-1);
		}
		assertEquals(TooltipScroll.MIN_SCALE, s.scale(), 0.0001);
		s.begin(100, 400, 40, T + 1, true, 1);
		assertEquals(TooltipScroll.MIN_SCALE, s.scale(), 0.0001, "the same tooltip keeps its scale");
		s.begin(120, 80, 8, T + 2, true, 0.75F);
		assertEquals(0.75F, s.scale(), 0.0001, "a new tooltip starts at the default");
		assertEquals(TooltipScroll.MAX_SCALE, TooltipScroll.clampScale(9), 0.0001);
	}

	@Test
	void showingExpiresShortlyAfterTheLastFrame() {
		TooltipScroll s = tall(true);
		assertTrue(s.showing(T + 100_000_000L));
		assertFalse(s.showing(T + 200_000_000L));
	}
}

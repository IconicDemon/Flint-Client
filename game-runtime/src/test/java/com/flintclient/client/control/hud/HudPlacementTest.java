package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class HudPlacementTest {
	private static final float EPS = 1e-3F;
	/** GUI-unit screens: 1920×1080 at GUI scales 1-4, 1280×720 at 1-3, 854×480 at 1-2, and Minecraft's minimum. */
	private static final float[][] SCREENS = {{1920, 1080}, {960, 540}, {640, 360}, {480, 270}, {1280, 720}, {427, 240}, {854, 480},
		{320, 240}, {3840 / 3.0F, 2160 / 3.0F}};
	private static final float[] SCALES = {0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 2.0F};

	@Test
	void everyAnchorOnAKnownScreen() {
		float w = 40;
		float h = 10;
		assertAt(Anchor.TOP_LEFT, 4, 6);
		assertAt(Anchor.TOP, 144, 6);
		assertAt(Anchor.TOP_RIGHT, 276, 6);
		assertAt(Anchor.LEFT, 4, 121);
		assertAt(Anchor.CENTER, 144, 121);
		assertAt(Anchor.RIGHT, 276, 121);
		assertAt(Anchor.BOTTOM_LEFT, 4, 224);
		assertAt(Anchor.BOTTOM, 144, 224);
		assertAt(Anchor.BOTTOM_RIGHT, 276, 224);
		HudRect rect = HudPlacement.of(Anchor.TOP_RIGHT, 4, 6).toAbsolute(320, 240, w, h);
		assertEquals(new HudRect(276, 6, 40, 10), rect);
	}

	private static void assertAt(Anchor anchor, float left, float top) {
		HudPlacement placement = HudPlacement.of(anchor, 4, 6);
		assertEquals(left, placement.left(320, 40), EPS, anchor + " left");
		assertEquals(top, placement.top(240, 10), EPS, anchor + " top");
	}

	@Test
	void offsetsAreMeasuredFromTheAnchoredEdgeOnEveryScreenAndScale() {
		for (float[] screen : SCREENS) {
			for (float scale : SCALES) {
				float w = 52 * scale;
				float h = 31 * scale;
				for (Anchor anchor : Anchor.values()) {
					HudRect rect = new HudPlacement(anchor, 7, 5, scale).toAbsolute(screen[0], screen[1], w, h);
					float dx = switch ((int) (anchor.fx() * 2)) {
						case 0 -> rect.x();
						case 1 -> rect.centerX() - screen[0] / 2;
						default -> screen[0] - rect.right();
					};
					float dy = switch ((int) (anchor.fy() * 2)) {
						case 0 -> rect.y();
						case 1 -> rect.centerY() - screen[1] / 2;
						default -> screen[1] - rect.bottom();
					};
					String where = anchor + " on " + screen[0] + "x" + screen[1] + " at " + scale;
					assertEquals(7, dx, EPS, where);
					assertEquals(5, dy, EPS, where);
					assertTrue(rect.within(screen[0], screen[1]), where);
				}
			}
		}
	}

	@Test
	void resizingKeepsTheRelationToTheAnchor() {
		HudPlacement topRight = HudPlacement.of(Anchor.TOP_RIGHT, 4, 4);
		HudRect small = topRight.toAbsolute(320, 240, 92, 40);
		HudRect large = topRight.toAbsolute(1920, 1080, 92, 40);
		assertEquals(320 - small.right(), 1920 - large.right(), EPS, "same gap to the right edge");
		assertEquals(small.y(), large.y(), EPS);
		HudPlacement right = HudPlacement.of(Anchor.RIGHT, 4, 0);
		assertEquals(120, right.toAbsolute(320, 240, 40, 72).centerY(), EPS, "stays vertically centred");
		assertEquals(540, right.toAbsolute(1920, 1080, 40, 72).centerY(), EPS);
		HudPlacement bottom = HudPlacement.of(Anchor.BOTTOM, -10, 30);
		for (float[] screen : SCREENS) {
			HudRect rect = bottom.toAbsolute(screen[0], screen[1], 50, 20);
			assertEquals(screen[1] - 30, rect.bottom(), EPS);
			assertEquals(screen[0] / 2 - 10, rect.centerX(), EPS);
		}
	}

	@Test
	void clampingAlwaysKeepsTheModuleOnScreen() {
		Random random = new Random(26_3);
		for (int i = 0; i < 2000; i++) {
			float[] screen = SCREENS[random.nextInt(SCREENS.length)];
			Anchor anchor = Anchor.values()[random.nextInt(9)];
			float scale = SCALES[random.nextInt(SCALES.length)];
			HudPlacement placement = new HudPlacement(anchor, random.nextFloat() * 4000 - 2000, random.nextFloat() * 4000 - 2000, scale);
			float w = (10 + random.nextFloat() * 100) * scale;
			float h = (10 + random.nextFloat() * 80) * scale;
			HudRect drawn = placement.onScreen(screen[0], screen[1], w, h);
			assertTrue(drawn.within(screen[0] + EPS, screen[1] + EPS), placement + " drawn at " + drawn);
			assertEquals(drawn.x(), placement.clampedLeft(screen[0], w), 0);
			assertEquals(drawn.y(), placement.clampedTop(screen[1], h), 0);
			HudPlacement clamped = placement.clampOnScreen(screen[0], screen[1], w, h);
			assertEquals(anchor, clamped.anchor(), "clamping keeps the anchor");
			HudRect again = clamped.toAbsolute(screen[0], screen[1], w, h);
			assertEquals(drawn.x(), again.x(), 0.01F);
			assertEquals(drawn.y(), again.y(), 0.01F);
		}
	}

	@Test
	void anOnScreenModuleIsNotMovedByClamping() {
		HudPlacement placement = HudPlacement.of(Anchor.BOTTOM_RIGHT, 10, 10);
		assertEquals(placement, placement.clampOnScreen(640, 360, 50, 50));
	}

	@Test
	void aModuleLargerThanTheScreenIsPinnedToTheTopLeft() {
		HudPlacement placement = HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4);
		HudRect drawn = placement.onScreen(320, 240, 400, 300);
		assertEquals(0, drawn.x());
		assertEquals(0, drawn.y());
	}

	@Test
	void anchorComesFromTheThirdTheCentreIsIn() {
		assertEquals(Anchor.TOP_LEFT, Anchor.forCenter(10, 10, 300, 300));
		assertEquals(Anchor.TOP, Anchor.forCenter(150, 10, 300, 300));
		assertEquals(Anchor.TOP_RIGHT, Anchor.forCenter(290, 10, 300, 300));
		assertEquals(Anchor.LEFT, Anchor.forCenter(10, 150, 300, 300));
		assertEquals(Anchor.CENTER, Anchor.forCenter(150, 150, 300, 300));
		assertEquals(Anchor.RIGHT, Anchor.forCenter(290, 150, 300, 300));
		assertEquals(Anchor.BOTTOM_LEFT, Anchor.forCenter(10, 290, 300, 300));
		assertEquals(Anchor.BOTTOM, Anchor.forCenter(150, 290, 300, 300));
		assertEquals(Anchor.BOTTOM_RIGHT, Anchor.forCenter(290, 290, 300, 300));
		assertEquals(Anchor.TOP, Anchor.forCenter(100, 0, 300, 300), "exactly on a third line counts as the middle");
		assertEquals(Anchor.TOP, Anchor.forCenter(200, 0, 300, 300));
		assertEquals(Anchor.TOP_LEFT, Anchor.of(-1, -5), "clamped");
		assertEquals(Anchor.BOTTOM_RIGHT, Anchor.of(9, 9));
	}

	@Test
	void reAnchoringKeepsTheVisualPosition() {
		Random random = new Random(42);
		for (int i = 0; i < 1000; i++) {
			float[] screen = SCREENS[random.nextInt(SCREENS.length)];
			float scale = SCALES[random.nextInt(SCALES.length)];
			float w = (20 + random.nextFloat() * 60) * scale;
			float h = (10 + random.nextFloat() * 40) * scale;
			float left = random.nextFloat() * (screen[0] - w);
			float top = random.nextFloat() * (screen[1] - h);
			HudPlacement placement = HudPlacement.fromAbsolute(left, top, w, h, screen[0], screen[1], scale);
			assertEquals(Anchor.forCenter(left + w / 2, top + h / 2, screen[0], screen[1]), placement.anchor());
			assertEquals(scale, placement.scale());
			assertEquals(left, placement.left(screen[0], w), 0.01F);
			assertEquals(top, placement.top(screen[1], h), 0.01F);
		}
	}

	@Test
	void droppingInTheBottomRightCornerAnchorsThereWithInwardOffsets() {
		HudPlacement placement = HudPlacement.fromAbsolute(600, 330, 30, 20, 640, 360, 1);
		assertEquals(Anchor.BOTTOM_RIGHT, placement.anchor());
		assertEquals(10, placement.x(), EPS);
		assertEquals(10, placement.y(), EPS);
		HudPlacement moved = HudPlacement.of(Anchor.TOP_LEFT, 600, 330).reanchored(640, 360, 30, 20);
		assertEquals(placement, moved);
	}

	@Test
	void movingIsInScreenDirectionsWhateverTheAnchor() {
		HudPlacement right = HudPlacement.of(Anchor.BOTTOM_RIGHT, 10, 10).moved(3, 2);
		assertEquals(7, right.x(), EPS, "moving right shrinks a right anchor's offset");
		assertEquals(8, right.y(), EPS);
		HudPlacement left = HudPlacement.of(Anchor.TOP_LEFT, 10, 10).moved(3, 2);
		assertEquals(13, left.x(), EPS);
		assertEquals(12, left.y(), EPS);
		HudRect before = HudPlacement.of(Anchor.CENTER, 0, 0).toAbsolute(320, 240, 20, 20);
		HudRect after = HudPlacement.of(Anchor.CENTER, 0, 0).moved(-5, 5).toAbsolute(320, 240, 20, 20);
		assertEquals(before.x() - 5, after.x(), EPS);
		assertEquals(before.y() + 5, after.y(), EPS);
	}

	@Test
	void valuesAreKeptUsable() {
		HudPlacement placement = new HudPlacement(null, Float.NaN, Float.POSITIVE_INFINITY, 1.1F);
		assertEquals(Anchor.TOP_LEFT, placement.anchor());
		assertEquals(0, placement.x());
		assertEquals(0, placement.y());
		assertEquals(1.0F, placement.scale(), "snapped to a size step");
		assertEquals(2.0F, placement.withScale(9).scale());
		HudPlacement edge = HudPlacement.fromAbsolute(280, 200, 40, 40, 320, 240, 1);
		assertEquals(Anchor.BOTTOM_RIGHT, edge.anchor());
		assertEquals(new HudPlacement(Anchor.BOTTOM_RIGHT, 0, 0, 1), edge, "no negative zero offsets");
		assertEquals(new HudPlacement(Anchor.LEFT, 1, 2, 1), HudPlacement.of(Anchor.LEFT, 5, 5).withOffset(1, 2));
	}

	@Test
	void scaleSteps() {
		assertEquals(6, ScaleSteps.count());
		assertEquals(0.5F, ScaleSteps.min());
		assertEquals(2.0F, ScaleSteps.max());
		assertEquals(0.75F, ScaleSteps.snap(0.8F));
		assertEquals(1.25F, ScaleSteps.snap(1.2F));
		assertEquals(1.0F, ScaleSteps.snap(Float.NaN));
		assertEquals(0.5F, ScaleSteps.snap(-3));
		assertEquals(2.0F, ScaleSteps.snap(99));
		assertEquals(1.25F, ScaleSteps.next(1.0F));
		assertEquals(2.0F, ScaleSteps.next(2.0F), "stops at the largest");
		assertEquals(0.75F, ScaleSteps.previous(1.0F));
		assertEquals(0.5F, ScaleSteps.previous(0.5F), "stops at the smallest");
		assertEquals(1.5F, ScaleSteps.next(1.3F), "from the nearest step");
		assertEquals(0.5F, ScaleSteps.snap(0.625F), "a tie goes to the smaller step");
		float scale = ScaleSteps.min();
		for (int i = 1; i < ScaleSteps.count(); i++) {
			float next = ScaleSteps.next(scale);
			assertTrue(next > scale);
			assertEquals(scale, ScaleSteps.previous(next));
			scale = next;
		}
	}

	@Test
	void rectangles() {
		HudRect a = new HudRect(0, 0, 10, 10);
		assertTrue(a.overlaps(new HudRect(5, 5, 10, 10)));
		assertTrue(!a.overlaps(new HudRect(10, 0, 10, 10)), "touching edges don't overlap");
		assertTrue(!a.overlaps(new HudRect(0, 10, 10, 10)));
		assertEquals(5, a.centerX());
		assertTrue(a.within(10, 10));
		assertTrue(!new HudRect(-1, 0, 5, 5).within(10, 10));
	}
}

package com.flintclient.client.control.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.ScaleSteps;
import org.junit.jupiter.api.Test;

class EditorMathTest {
	@Test
	void theInspectorGoesOnTheSideAwayFromTheModule() {
		assertFalse(EditorMath.inspectorOnLeft(20, 480), "module on the left: sheet on the right");
		assertTrue(EditorMath.inspectorOnLeft(460, 480), "module on the right: sheet on the left");
		assertFalse(EditorMath.inspectorOnLeft(240, 480), "exactly centred: the right, as usual");
		assertTrue(EditorMath.inspectorOnLeft(241, 480));
	}

	@Test
	void draggingTheHandleScalesByDistanceInWholeSteps() {
		assertEquals(2.0F, EditorMath.scaleForDrag(1.0F, 20, 40), "twice as far: twice the size");
		assertEquals(0.5F, EditorMath.scaleForDrag(1.0F, 20, 10));
		assertEquals(1.25F, EditorMath.scaleForDrag(1.0F, 20, 26), "1.3 snaps to the nearest step");
		assertEquals(1.5F, EditorMath.scaleForDrag(0.75F, 10, 20));
		assertEquals(ScaleSteps.max(), EditorMath.scaleForDrag(1.0F, 10, 500), "never past the largest step");
		assertEquals(ScaleSteps.min(), EditorMath.scaleForDrag(1.0F, 10, 0), "behind the corner: the smallest step");
		assertEquals(1.0F, EditorMath.scaleForDrag(1.0F, 0, 30), "a press on the corner itself changes nothing");
		assertEquals(1.0F, EditorMath.scaleForDrag(1.0F, 10, Float.NaN));
		for (int i = 0; i < 400; i++) {
			float scale = EditorMath.scaleForDrag(1.25F, 37, i * 0.5F);
			assertEquals(ScaleSteps.snap(scale), scale, "always a size step");
		}
	}

	@Test
	void distanceAndHandleHits() {
		assertEquals(5.0F, EditorMath.distance(0, 0, 3, 4));
		assertTrue(EditorMath.inHandle(100, 50, 100, 50, 8));
		assertTrue(EditorMath.inHandle(104, 46, 100, 50, 8), "the edges of the handle count");
		assertFalse(EditorMath.inHandle(104.5F, 50, 100, 50, 8));
	}

	@Test
	void nudgesAreOneUnitTenWithCtrlAndHalfWithShift() {
		assertEquals(1.0F, EditorMath.nudge(false, false));
		assertEquals(10.0F, EditorMath.nudge(true, false));
		assertEquals(0.5F, EditorMath.nudge(false, true));
		assertEquals(10.0F, EditorMath.nudge(true, true), "Ctrl wins: a large move");
	}

	@Test
	void thePaletteSearchMatchesEveryWordOfANameOrId() {
		assertTrue(EditorMath.widgetMatches("", "Armor Status", "armor_status"));
		assertTrue(EditorMath.widgetMatches("armor", "Armor Status", "armor_status"));
		assertTrue(EditorMath.widgetMatches("  STATUS arm ", "Armor Status", "armor_status"));
		assertTrue(EditorMath.widgetMatches("cps", "Clicks per second", "cps"), "the id counts too");
		assertFalse(EditorMath.widgetMatches("armor fps", "Armor Status", "armor_status"));
		assertFalse(EditorMath.widgetMatches("ping", "FPS", "fps"));
	}

	@Test
	void theDotGridIsSixteenUnitsAtEveryScale() {
		assertEquals(16, EditorMath.gridSpacingPx(1), "16 units at GUI scale 1, however big the window");
		assertEquals(32, EditorMath.gridSpacingPx(2));
		assertEquals(48, EditorMath.gridSpacingPx(3));
		assertEquals(16, EditorMath.gridSpacingPx(0), "a zero scale still gives a usable spacing");
	}

	@Test
	void overlapAndClamping() {
		assertTrue(EditorMath.overlaps(0, 0, 10, 10, 5, 5, 10, 10));
		assertFalse(EditorMath.overlaps(0, 0, 10, 10, 10, 0, 10, 10), "touching edges don't overlap");
		assertFalse(EditorMath.overlaps(0, 0, 10, 10, 0, 11, 10, 10));
		assertEquals(0.0F, EditorMath.clampInto(-4, 100));
		assertEquals(100.0F, EditorMath.clampInto(140, 100));
		assertEquals(37.5F, EditorMath.clampInto(37.5F, 100));
		assertEquals(0.0F, EditorMath.clampInto(12, -3), "wider than the screen: at the left edge");
	}
}

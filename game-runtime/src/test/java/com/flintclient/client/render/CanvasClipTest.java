package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

class CanvasClipTest {
	private static int[] clip(int x0, int y0, int x1, int y1, int guiScale) {
		int[] out = new int[4];
		Canvas.toGuiInside(x0, y0, x1, y1, guiScale, out);
		return out;
	}

	@Test
	void pixelRectanglesRoundInwardsToWholeGuiUnits() {
		assertArrayEquals(new int[] {2, 3, 10, 20}, clip(6, 9, 30, 60, 3), "already aligned");
		// 4..20 px at scale 3: GUI units 2..6 (6..18 px), never past the pixel rectangle.
		assertArrayEquals(new int[] {2, 2, 6, 6}, clip(4, 4, 20, 20, 3));
		assertArrayEquals(new int[] {4, 7, 17, 13}, clip(4, 7, 17, 13, 1), "scale 1 is exact");
	}

	@Test
	void tinyOrInvertedRectanglesBecomeEmptyNotInverted() {
		assertArrayEquals(new int[] {2, 2, 2, 2}, clip(4, 4, 5, 5, 3));
		assertArrayEquals(new int[] {4, 4, 4, 4}, clip(10, 10, 2, 2, 3));
	}

	@Test
	void theScissorPoseIsExactlyTheEnclosingTransform() {
		for (int g = 1; g <= 12; g++) {
			// What the outermost begin leaves on the pose: an enclosing transform, then 1/g.
			Matrix3x2f enclosing = new Matrix3x2f().translate(7, -3);
			Matrix3x2f pixelPose = new Matrix3x2f(enclosing).scale(1.0F / g, 1.0F / g);
			Matrix3x2f scissor = new Matrix3x2f();
			Canvas.scissorPose(scissor, pixelPose, g);
			assertTrue(scissor.equals(enclosing), "GUI scale " + g + ": " + scissor);
		}
	}

	@Test
	void aClipUnderAnOffsetLandsInsideTheMovedRectangle() {
		int g = 3;
		// A slide moved the content down 4 px (not a whole GUI unit); the view is 0..30 x 10..40 px before it.
		int offset = 4;
		int[] gui = clip(0, 10 + offset, 30, 40 + offset, g);
		Matrix3x2f scissor = new Matrix3x2f();
		Canvas.scissorPose(scissor, new Matrix3x2f().scale(1.0F / g, 1.0F / g), g);
		ScreenRectangle landed = new ScreenRectangle(gui[0], gui[1], gui[2] - gui[0], gui[3] - gui[1]).transformAxisAligned(scissor);
		// GuiRenderer multiplies by the GUI scale: the scissor in pixels must sit inside 14..44.
		assertTrue(landed.top() * g >= 10 + offset, "top " + landed.top() * g);
		assertTrue(landed.bottom() * g <= 40 + offset, "bottom " + landed.bottom() * g);
		assertEquals(15, landed.top() * g, "rounded in by at most a GUI unit");
		assertEquals(42, landed.bottom() * g);
	}

	@Test
	void nestedBeginsStackTheirScaleAndTheOutermostResets() {
		Canvas canvas = new Canvas(3);
		canvas.enter(0);
		assertEquals(3, canvas.scale(), "0 means the GUI scale");
		canvas.setOpacity(0.4F);
		canvas.enter(2);
		assertEquals(2, canvas.scale(), "a kit screen's own interface scale");
		canvas.enter(5);
		assertEquals(5, canvas.scale());
		assertFalse(canvas.exit());
		assertEquals(2, canvas.scale());
		assertFalse(canvas.exit());
		assertEquals(3, canvas.scale());
		assertEquals(0.4F, canvas.opacity(), "nested begins keep the fade");
		assertTrue(canvas.exit(), "the outermost end");
		assertEquals(0, canvas.depth());
		assertFalse(canvas.exit(), "an unbalanced end does nothing");
		assertEquals(0, canvas.depth());
		canvas.enter(0);
		assertEquals(1.0F, canvas.opacity(), "a new frame starts opaque");
		assertEquals(0, canvas.openClips());
		assertEquals(3, canvas.scale());
	}

	@Test
	void theScaleStackGrowsPastItsFirstSize() {
		Canvas canvas = new Canvas(2);
		for (int i = 1; i <= 20; i++) {
			canvas.enter(i);
		}
		for (int i = 20; i >= 2; i--) {
			assertEquals(i, canvas.scale());
			canvas.exit();
		}
		assertEquals(1, canvas.scale());
		assertTrue(canvas.exit());
	}
}

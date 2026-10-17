package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DroppedItemPoseTest {
	@Test
	void tumbleFollowsTheSpeed() {
		assertEquals(0, DroppedItemPose.tumble(0, 100, 0, false), 1e-4);
		assertEquals(20, DroppedItemPose.tumble(1, 100, 0, false), 1e-4);
		assertEquals(10, DroppedItemPose.tumble(1, 50, 0, false), 1e-4);
		assertEquals(5, DroppedItemPose.tumble(1, 100, 0, true), 1e-4, "a quarter as fast in water");
		assertEquals(0, DroppedItemPose.tumble(500, 0, 0, false), 1e-4, "0% never turns");
	}

	@Test
	void settlingTakesTheShortWayAndStops() {
		assertEquals(170, DroppedItemPose.settle(170, 90, 0), 1e-4);
		assertEquals(90, DroppedItemPose.settle(170, 90, 3), 1e-4);
		assertEquals(90, DroppedItemPose.settle(170, 90, 300), 1e-4);
		float mid = DroppedItemPose.settle(-170, 90, 1.5F);
		assertTrue(mid < -170 || mid > 90, "goes from -170 down through 180 to 90, not up through 0: " + mid);
	}

	@Test
	void liftSitsTheTurnedBoxOnTheGround() {
		assertEquals(0.26F, DroppedItemPose.lift(0.5F, 0.0625F, 0), 1e-4);
		assertEquals(0.04125F, DroppedItemPose.lift(0.5F, 0.0625F, 90), 1e-4);
	}

	@Test
	void wrapStaysInOneTurn() {
		assertEquals(180, DroppedItemPose.wrap(540), 1e-4);
		assertEquals(-90, DroppedItemPose.wrap(270), 1e-4);
	}
}

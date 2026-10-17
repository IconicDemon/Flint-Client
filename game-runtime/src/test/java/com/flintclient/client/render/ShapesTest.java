package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShapesTest {
	@Test
	void coverageIsAOnePixelRampAcrossTheEdge() {
		assertEquals(1.0F, Sdf.coverage(-2.0F));
		assertEquals(0.5F, Sdf.coverage(0.0F));
		assertEquals(0.0F, Sdf.coverage(2.0F));
	}

	@Test
	void polygonDistanceIsNegativeInsideAndPositiveOutside() {
		Sdf.Polygon square = new Sdf.Polygon(new float[] {0, 10, 10, 0}, new float[] {0, 0, 10, 10}, 0);
		assertEquals(-5.0F, square.signedDistance(5, 5), 1e-4);
		assertEquals(3.0F, square.signedDistance(13, 5), 1e-4);
		assertEquals(0.0F, square.signedDistance(10, 5), 1e-4);
	}

	@Test
	void pathDistanceMeasuresToTheNearestSegment() {
		Sdf.Path line = new Sdf.Path().move(0, 0).line(10, 0);
		assertEquals(2.0F, line.distance(5, 2), 1e-4);
		assertEquals(5.0F, line.distance(13, 4), 1e-4);
		Sdf.Path circle = new Sdf.Path().circle(12, 12, 4);
		assertEquals(0.0F, circle.distance(16, 12), 0.05);
	}

	@Test
	void everyIconStaysInsideItsGrid() {
		for (Icon icon : Icon.values()) {
			assertFalse(icon.path().segments.isEmpty(), icon + " has no strokes");
			for (float[] s : icon.path().segments) {
				for (float v : s) {
					assertTrue(v >= 0 && v <= Icon.UNITS, icon + " leaves the 24-unit grid: " + v);
				}
			}
		}
	}

	@Test
	void roundedRectangleDistanceIsZeroOnTheEdgeAndNegativeInside() {
		assertEquals(0.0F, MaskAtlas.roundRectDistance(0, 5, 20, 10, 3), 1e-4, "left edge");
		assertEquals(-5.0F, MaskAtlas.roundRectDistance(10, 5, 20, 10, 3), 1e-4, "centre");
		// The corner is rounded: the bounding-box corner itself lies outside the shape.
		assertEquals(3 * (float) Math.sqrt(2) - 3, MaskAtlas.roundRectDistance(0, 0, 20, 10, 3), 1e-4);
	}

	@Test
	void theMarkKeepsTheBrandProportions() {
		assertEquals(190.0F / 350.0F, MaskAtlas.Logo.ASPECT, 1e-6);
		assertEquals(54, MaskAtlas.markWidth(100));
	}
}

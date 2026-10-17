package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnapperTest {
	private static final float W = 320;
	private static final float H = 240;

	private static Snapper snapper() {
		Snapper snapper = new Snapper();
		snapper.begin(W, H);
		return snapper;
	}

	@Test
	void snapsToTheInsetScreenEdges() {
		Snapper snapper = snapper();
		assertTrue(snapper.snap(5, 100, 40, 10, false));
		assertEquals(2, snapper.left(), "left edge to the left inset");
		assertTrue(snapper.hasVerticalGuide());
		assertEquals(2, snapper.verticalGuideX());
		assertEquals(Snapper.Source.SCREEN_EDGE, snapper.verticalGuideSource());
		assertEquals(0, snapper.verticalGuideFrom());
		assertEquals(H, snapper.verticalGuideTo(), "a screen guide spans the screen");
		assertEquals(100, snapper.top(), "nothing near vertically");
		assertFalse(snapper.hasHorizontalGuide());

		snapper.snap(275, 226, 40, 10, false);
		assertEquals(278, snapper.left(), "right edge to the right inset");
		assertEquals(228, snapper.top(), "bottom edge to the bottom inset");
		assertEquals(H - 2, snapper.horizontalGuideY());
		assertEquals(Snapper.Source.SCREEN_EDGE, snapper.horizontalGuideSource());
	}

	@Test
	void snapsTheCentreToTheScreenCentreLines() {
		Snapper snapper = snapper();
		snapper.snap(138, 113, 40, 10, false);
		assertEquals(140, snapper.left());
		assertEquals(115, snapper.top());
		assertEquals(Snapper.Source.SCREEN_CENTER, snapper.verticalGuideSource());
		assertEquals(Snapper.Source.SCREEN_CENTER, snapper.horizontalGuideSource());
		assertEquals(160, snapper.verticalGuideX());
		assertEquals(120, snapper.horizontalGuideY());
	}

	@Test
	void theThresholdIsFourUnitsInclusive() {
		Snapper snapper = snapper();
		assertTrue(snapper.snap(6, 100, 40, 10, false));
		assertEquals(2, snapper.left(), "exactly 4 away still snaps");
		assertFalse(snapper.snap(6.01F, 100, 40, 10, false));
		assertEquals(6.01F, snapper.left(), "just over 4 stays put");
		assertFalse(snapper.hasVerticalGuide());
		assertEquals(4.0F, Snapper.THRESHOLD);
		assertEquals(2.0F, Snapper.EDGE_INSET);
	}

	@Test
	void snapsToOtherModulesEdgesAndCentres() {
		Snapper snapper = snapper();
		snapper.addModule(100, 100, 50, 20);
		snapper.snap(152, 60, 30, 10, false);
		assertEquals(150, snapper.left(), "left edge to the other's right edge (side by side)");
		assertEquals(Snapper.Source.MODULE, snapper.verticalGuideSource());
		assertEquals(150, snapper.verticalGuideX());
		assertEquals(60, snapper.verticalGuideFrom(), "the guide runs from the dragged module…");
		assertEquals(120, snapper.verticalGuideTo(), "…to the other one");

		snapper.snap(60, 98, 30, 10, false);
		assertEquals(100, snapper.top(), "top edge to the other's top edge");
		assertEquals(Snapper.Source.MODULE, snapper.horizontalGuideSource());
		assertEquals(60, snapper.horizontalGuideFrom());
		assertEquals(150, snapper.horizontalGuideTo());

		snapper.snap(108, 60, 30, 10, false);
		assertEquals(110, snapper.left(), "centre to centre");
	}

	@Test
	void theNearestGuideWins() {
		Snapper snapper = snapper();
		snapper.addModule(100, 100, 50, 20);
		// Left edges are 3 apart (100 vs 103); centres are 2 apart (125 vs 123).
		snapper.snap(103, 60, 40, 10, false);
		assertEquals(105, snapper.left(), "the centre is nearer");
		// A second module even nearer.
		snapper.addModule(104, 200, 10, 10);
		snapper.snap(103, 60, 40, 10, false);
		assertEquals(104, snapper.left());
		assertEquals(104, snapper.verticalGuideX());
	}

	@Test
	void onATieScreenGuidesBeatModulesAndModulesBeatAreas() {
		Snapper snapper = snapper();
		snapper.addArea(2, 150, 20, 20);
		snapper.addModule(2, 50, 20, 20);
		snapper.snap(4, 100, 30, 10, false);
		assertEquals(2, snapper.left());
		assertEquals(Snapper.Source.SCREEN_EDGE, snapper.verticalGuideSource(), "the screen edge first");

		snapper.begin(W, H);
		snapper.addArea(80, 150, 20, 20);
		snapper.addModule(80, 50, 20, 20);
		snapper.snap(82, 100, 30, 10, false);
		assertEquals(80, snapper.left());
		assertEquals(Snapper.Source.MODULE, snapper.verticalGuideSource(), "modules before areas, whatever the order added");
	}

	@Test
	void minecraftAreasAreGuidesToo() {
		Snapper snapper = snapper();
		// The hotbar: 182×22 at the bottom centre.
		snapper.addArea((W - 182) / 2, H - 22, 182, 22);
		snapper.snap(40, H - 22 - 10 - 3, 30, 10, false);
		assertEquals(H - 22 - 10, snapper.top(), "sits right on top of the hotbar");
		assertEquals(Snapper.Source.AREA, snapper.horizontalGuideSource());
	}

	@Test
	void altBypassesSnapping() {
		Snapper snapper = snapper();
		snapper.addModule(100, 100, 50, 20);
		assertFalse(snapper.snap(5, 101, 40, 10, true));
		assertEquals(5, snapper.left());
		assertEquals(101, snapper.top());
		assertFalse(snapper.hasVerticalGuide());
		assertFalse(snapper.hasHorizontalGuide());
	}

	@Test
	void beginForgetsThePreviousModules() {
		Snapper snapper = snapper();
		snapper.addModule(100, 100, 50, 20);
		snapper.begin(W, H);
		assertFalse(snapper.snap(103, 60, 40, 10, false));
		assertEquals(103, snapper.left());
	}

	@Test
	void manyModulesGrowTheArrays() {
		Snapper snapper = snapper();
		for (int i = 0; i < 100; i++) {
			snapper.addModule(1000 + i * 10, 1000, 5, 5);
		}
		snapper.addModule(100, 100, 50, 20);
		snapper.snap(152, 60, 30, 10, false);
		assertEquals(150, snapper.left());
	}
}

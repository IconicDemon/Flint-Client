package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FocusNavTest {
	/** A focusable test node. */
	private static final class Stop extends Node {
		@Override
		public boolean focusable() {
			return true;
		}
	}

	private static Stop at(float x, float y, float w, float h) {
		Stop stop = new Stop();
		stop.setBounds(x, y, w, h);
		return stop;
	}

	@Test
	void tabOrderIsTreeOrderSkippingHiddenAndDisabledNodes() {
		Node root = new Node();
		Stop a = root.add(at(0, 0, 10, 10));
		Node group = root.add(new Node());
		Stop b = group.add(at(0, 20, 10, 10));
		Stop hidden = group.add(at(0, 40, 10, 10));
		hidden.setVisible(false);
		Stop off = root.add(at(0, 60, 10, 10));
		off.setEnabled(false);
		Stop explained = root.add(at(0, 80, 10, 10));
		explained.setDisabled("Join a world to edit your HUD");
		Node hiddenGroup = root.add(new Node());
		hiddenGroup.add(at(0, 100, 10, 10));
		hiddenGroup.setVisible(false);
		List<Node> order = new ArrayList<>();
		root.collectFocusable(order);
		assertEquals(List.of(a, b, explained), order, "disabled with a reason stays reachable, to read the reason");
	}

	@Test
	void tabAndShiftTabWrapAround() {
		Stop a = at(0, 0, 10, 10);
		Stop b = at(0, 20, 10, 10);
		Stop c = at(0, 40, 10, 10);
		List<Node> order = List.of(a, b, c);
		assertSame(b, FocusNav.next(order, a, true));
		assertSame(a, FocusNav.next(order, c, true));
		assertSame(c, FocusNav.next(order, a, false));
		assertSame(a, FocusNav.next(order, null, true));
		assertSame(c, FocusNav.next(order, null, false));
		assertNull(FocusNav.next(List.of(), null, true));
	}

	@Test
	void arrowsMoveThroughAGridOfCards() {
		// Two rows of three 118×54 cards with 6-unit gaps.
		Stop[][] grid = new Stop[2][3];
		List<Node> order = new ArrayList<>();
		for (int row = 0; row < 2; row++) {
			for (int col = 0; col < 3; col++) {
				grid[row][col] = at(col * 124, row * 60, 118, 54);
				order.add(grid[row][col]);
			}
		}
		assertSame(grid[0][1], FocusNav.spatial(order, grid[0][0], 1, 0));
		assertSame(grid[1][0], FocusNav.spatial(order, grid[0][0], 0, 1));
		assertSame(grid[1][2], FocusNav.spatial(order, grid[0][2], 0, 1), "straight down, not diagonally");
		assertSame(grid[0][1], FocusNav.spatial(order, grid[1][1], 0, -1));
		assertNull(FocusNav.spatial(order, grid[0][0], -1, 0), "nothing further left");
		assertNull(FocusNav.spatial(order, grid[1][2], 0, 1));
	}

	@Test
	void inLineCandidatesBeatCloserDiagonalOnes() {
		Stop from = at(0, 0, 20, 20);
		Stop diagonal = at(30, 30, 20, 20);
		Stop inLine = at(80, 0, 20, 20);
		assertSame(inLine, FocusNav.spatial(List.of(from, diagonal, inLine), from, 1, 0));
	}

	@Test
	void overlapGapIsZeroWhenRangesOverlap() {
		assertEquals(0, FocusNav.overlapGap(0, 10, 5, 10));
		assertEquals(5, FocusNav.overlapGap(0, 10, 15, 4));
		assertEquals(5, FocusNav.overlapGap(15, 4, 0, 10));
	}
}

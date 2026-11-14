package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link QuickTile}'s names: where a name too wide for its room breaks onto two lines. */
class QuickTileTest {
	@Test
	void namesBreakAtTheSpaceNearestTheirMiddle() {
		assertEquals(9, QuickTile.breakAt("MINECRAFT FOLDER"));
		assertEquals(2, QuickTile.breakAt("MC SETTINGS"));
		assertEquals(14, QuickTile.breakAt("FLINT RESOURCE PACKS"));
		assertEquals(-1, QuickTile.breakAt("SCREENSHOTS"));
		assertEquals(-1, QuickTile.breakAt(""));
	}
}

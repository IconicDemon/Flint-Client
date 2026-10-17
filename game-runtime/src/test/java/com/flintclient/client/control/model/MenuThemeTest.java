package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuThemeTest {
	@Test
	void valuesAreKeptInRange() {
		MenuTheme t = new MenuTheme(0xFF123456, null, 999, -5);
		assertEquals(0x123456, t.accentRgb(), "no alpha");
		assertEquals(MenuTheme.Surface.CHARCOAL, t.surface());
		assertEquals(MenuTheme.MAX_ROUNDNESS, t.roundness());
		assertEquals(MenuTheme.MIN_DIM, t.dim());
	}

	@Test
	void readingFallsBackToFlintPieceByPiece() {
		assertEquals(MenuTheme.FLINT, MenuTheme.read(null, null, null, null));
		assertEquals(MenuTheme.FLINT, MenuTheme.read("not a colour", "plaid", null, null));
		MenuTheme t = MenuTheme.read("#3366FF", "midnight", 70, 40);
		assertEquals(0x3366FF, t.accentRgb());
		assertEquals(MenuTheme.Surface.MIDNIGHT, t.surface());
		assertEquals(70, t.roundness());
		assertEquals(40, t.dim());
		assertEquals("#3366FF", t.accentHex());
	}

	@Test
	void presetsAreDistinctAndRecognised() {
		Set<MenuTheme> seen = new HashSet<>();
		Set<String> ids = new HashSet<>();
		for (MenuTheme.Preset preset : MenuTheme.PRESETS) {
			assertTrue(seen.add(preset.theme()), preset.name());
			assertTrue(ids.add(preset.id()));
			assertSame(preset, preset.theme().preset());
		}
		assertNull(MenuTheme.FLINT.withRoundness(73).preset(), "an edited theme is custom");
	}

	@Test
	void charcoalKeepsFlintsColoursAndOthersKeepLightAndAlpha() {
		int window = 0xFF211E1B;
		assertEquals(window, MenuTheme.FLINT.tint(window));
		MenuTheme graphite = MenuTheme.FLINT.withSurface(MenuTheme.Surface.GRAPHITE);
		int grey = graphite.tint(window);
		assertEquals((grey >> 16) & 0xFF, grey & 0xFF, "graphite is neutral grey");
		assertEquals(0xFF, grey >>> 24);
		assertEquals(0x40, MenuTheme.FLINT.withSurface(MenuTheme.Surface.ONYX).tint(0x40FFFFFF) >>> 24, "alpha kept");
		MenuTheme onyx = MenuTheme.FLINT.withSurface(MenuTheme.Surface.ONYX);
		assertTrue((onyx.tint(window) & 0xFF) < (grey & 0xFF), "onyx is darker");
		int midnight = MenuTheme.FLINT.withSurface(MenuTheme.Surface.MIDNIGHT).tint(window);
		assertTrue((midnight & 0xFF) > (midnight >> 16 & 0xFF), "midnight leans blue");
		assertNotEquals(window, midnight);
		int text = 0xFFF6F1EB;
		assertTrue((onyx.tint(text) & 0xFF) > 0xE0, "light text stays light on a darker family");
	}
}

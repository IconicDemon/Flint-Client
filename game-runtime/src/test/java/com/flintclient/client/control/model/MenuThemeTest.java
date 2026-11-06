package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The menu's theme: the app's five looks and two fonts, read back from what was saved. */
class MenuThemeTest {
	@Test
	void missingPartsAreFlintsOwn() {
		assertEquals(MenuTheme.FLINT, new MenuTheme(null, null));
		assertEquals(MenuTheme.FLINT, MenuTheme.read(null, null));
		assertEquals(MenuTheme.FLINT, MenuTheme.read("plaid", "comic"));
	}

	@Test
	void savedPartsReadBack() {
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			for (MenuTheme.Font font : MenuTheme.Font.values()) {
				assertEquals(new MenuTheme(look, font), MenuTheme.read(look.id(), font.id()));
			}
		}
	}

	@Test
	void flintIsDawnsSlateWhiteAndOrange() {
		assertEquals(MenuTheme.Look.FLINT, MenuTheme.FLINT.look());
		assertEquals(0x0D0F12, MenuTheme.Look.FLINT.background);
		assertEquals(0xEDEDED, MenuTheme.Look.FLINT.ink);
		assertEquals(0xF97316, MenuTheme.FLINT.accentRgb());
		assertEquals(5, MenuTheme.Look.values().length, "Ember (Flint's own), Obsidian, Moss, Bloodstone and Dusk");
		assertEquals("Ember", MenuTheme.Look.FLINT.label());
		assertEquals("flint", MenuTheme.Look.FLINT.id(), "the id saved settings use is kept");
	}

	@Test
	void theRemovedEmberLookReadsAsFlintsOwn() {
		assertEquals(MenuTheme.Look.FLINT, MenuTheme.read("ember", "default").look());
	}
}

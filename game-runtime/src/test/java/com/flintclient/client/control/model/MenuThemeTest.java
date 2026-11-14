package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertEquals(6, MenuTheme.Look.values().length, "Orange (Flint's own), Ice Cream, Mint, Cherry, Passion Fruit and Blueberry");
		assertEquals(java.util.List.of("Orange", "Ice Cream", "Mint", "Cherry", "Passion Fruit", "Blueberry"),
			java.util.Arrays.stream(MenuTheme.Look.values()).map(MenuTheme.Look::label).toList(), "the names shown, no \"Flint \" prefix");
		assertEquals(java.util.List.of("flint", "icecream", "moss", "bloodstone", "dusk", "aurora"),
			java.util.Arrays.stream(MenuTheme.Look.values()).map(MenuTheme.Look::id).toList(), "the saved ids are kept");
		assertEquals("Orange", MenuTheme.Look.FLINT.label());
		assertEquals("flint", MenuTheme.Look.FLINT.id(), "the id saved settings use is kept");
	}

	@Test
	void theRemovedEmberLookReadsAsFlintsOwn() {
		assertEquals(MenuTheme.Look.FLINT, MenuTheme.read("ember", "default").look());
	}

	@Test
	void obsidianWasRemadeAsIceCreamTheLightTheme() {
		assertEquals(MenuTheme.Look.ICE_CREAM, MenuTheme.read("obsidian", "default").look(), "a saved Obsidian keeps working");
		assertEquals("icecream", MenuTheme.Look.ICE_CREAM.id());
		assertEquals(MenuTheme.Look.ICE_CREAM, MenuTheme.read("icecream", null).look());
		assertTrue(MenuTheme.Look.ICE_CREAM.light());
		for (MenuTheme.Look look : MenuTheme.Look.values()) {
			assertEquals(look == MenuTheme.Look.ICE_CREAM, look.light(), look + " light");
		}
	}
}

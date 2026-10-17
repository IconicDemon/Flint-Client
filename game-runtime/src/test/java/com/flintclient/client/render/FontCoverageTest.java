package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FontCoverageTest {
	@Test
	void latinTextUsesFlintsFontsAndOtherScriptsFallBack() {
		assertTrue(FlintFonts.LABEL.covers("Singleplayer"));
		assertTrue(FlintFonts.LABEL.covers("Tryb wieloosobowy – Łódź"), "Polish and dashes are covered");
		assertTrue(FlintFonts.LABEL.covers("Flint 0.4.0 · Minecraft 26.3"));
		assertFalse(FlintFonts.LABEL.covers("单人游戏"), "CJK falls back to Minecraft's font");
		assertFalse(FlintFonts.LABEL.covers("Одиночная игра"), "Cyrillic falls back to Minecraft's font");
		assertTrue(FlintFonts.WORDMARK.covers("FLINT"));
		assertFalse(FlintFonts.WORDMARK.covers("Ł"), "the wordmark font only has Latin-1");
	}

	@Test
	void capHeightScalesWithTheGuiScale() {
		float one = FlintFonts.LABEL.capHeightPx(1);
		assertTrue(Math.abs(FlintFonts.LABEL.capHeightPx(4) - 4 * one) < 1e-4);
	}
}

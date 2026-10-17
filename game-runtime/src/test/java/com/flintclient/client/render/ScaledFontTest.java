package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScaledFontTest {
	@Test
	void sizesAreQuantisedToTwentiethsAndKeptInRange() {
		assertEquals(460, ScaledFont.quantise(4.6F));
		assertEquals(575, ScaledFont.quantise(4.6F * 1.25F));
		assertEquals(345, ScaledFont.quantise(4.6F * 0.75F));
		assertEquals(345, ScaledFont.quantise(3.46F), "rounded to the nearest 0.05");
		assertEquals(50, ScaledFont.quantise(0.01F));
		assertEquals(6400, ScaledFont.quantise(500));
	}

	@Test
	void namesAreValidIdentifierPathsPerScale() {
		String name = ScaledFont.name("inter-semibold", 575, 3);
		assertEquals("scaled_inter-semibold_575_3", name);
		assertTrue(name.matches("[a-z0-9_.-]+"), name);
	}

	@Test
	void theSameFileAndSizeIsOneFace() {
		ScaledFont a = ScaledFont.of(FlintFonts.STRONG, 5.75F);
		assertSame(a, ScaledFont.of(FlintFonts.STRONG, 5.751F));
		assertSame(a, ScaledFont.scaled(FlintFonts.STRONG, 1.25F));
		assertEquals(5.75F, a.size(), 1e-6);
		// Same file as STRONG, so its coverage and cap height follow the role.
		assertEquals(FlintFonts.STRONG.capHeightPx(2) * 1.25F, a.capHeightPx(2), 1e-4);
		assertTrue(a.covers("Coordinates − 12"));
		assertFalse(a.covers("单人"));
	}

	@Test
	void theNumberOfFacesIsBounded() {
		ScaledFont last = null;
		for (int i = 0; i < ScaledFont.MAX_FACES + 20; i++) {
			last = ScaledFont.of(FlintFonts.LABEL, 1 + i * 0.05F);
		}
		// Past the limit a request gets the nearest existing face instead of a new font set.
		assertSame(last, ScaledFont.of(FlintFonts.LABEL, last.size()));
		assertTrue(ScaledFont.of(FlintFonts.LABEL, 40).size() < 40);
	}
}

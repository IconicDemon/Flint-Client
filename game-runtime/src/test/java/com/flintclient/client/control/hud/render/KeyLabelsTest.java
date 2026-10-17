package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyLabelsTest {
	@Test
	void singleKeysAreCapitals() {
		assertEquals("W", KeyLabels.keyboard("w"));
		assertEquals("A", KeyLabels.keyboard("A"));
		assertEquals(";", KeyLabels.keyboard(";"));
		assertEquals("Ä", KeyLabels.keyboard("ä"));
	}

	@Test
	void commonNamesGetTheUsualAbbreviations() {
		assertEquals("LSHFT", KeyLabels.keyboard("Left Shift"));
		assertEquals("RCTRL", KeyLabels.keyboard("Right Control"));
		assertEquals("SPACE", KeyLabels.keyboard("Space"));
		assertEquals("CAPS", KeyLabels.keyboard("Caps Lock"));
		assertEquals("UP", KeyLabels.keyboard("Up Arrow"));
		assertEquals("PGDN", KeyLabels.keyboard("Page Down"));
		assertEquals("LOPT", KeyLabels.keyboard("Left Option"), "macOS names its Alt keys Option");
		assertEquals("F12", KeyLabels.keyboard("F12"));
	}

	@Test
	void otherNamesAreCompactedAndKeptShort() {
		assertEquals("KP5", KeyLabels.keyboard("Keypad 5"));
		assertEquals("KP+", KeyLabels.keyboard("Keypad +"));
		assertEquals("KPENT", KeyLabels.keyboard("Keypad Enter"));
		assertEquals("INTER", KeyLabels.keyboard("International 1"));
		assertEquals("ENTER", KeyLabels.keyboard("enter"), "matching ignores case");
		for (String name : new String[] {"Alternate Erase", "AC Bookmarks", "Currency Subunit", "Keypad Hexadecimal"}) {
			assertTrue(KeyLabels.keyboard(name).codePointCount(0, KeyLabels.keyboard(name).length()) <= KeyLabels.MAX_CHARS, name);
		}
	}

	@Test
	void unboundAndBlankShowADash() {
		assertEquals(KeyLabels.UNBOUND, KeyLabels.keyboard(""));
		assertEquals(KeyLabels.UNBOUND, KeyLabels.keyboard("   "));
		assertEquals(KeyLabels.UNBOUND, KeyLabels.keyboard(null));
	}

	@Test
	void mouseButtonsAreNamedByButton() {
		assertEquals("LMB", KeyLabels.mouse(1));
		assertEquals("MMB", KeyLabels.mouse(2));
		assertEquals("RMB", KeyLabels.mouse(3));
		assertEquals("M4", KeyLabels.mouse(4));
		assertEquals("M8", KeyLabels.mouse(8));
	}
}

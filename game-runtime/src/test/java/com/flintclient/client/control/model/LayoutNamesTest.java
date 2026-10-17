package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LayoutNamesTest {
	@Test
	void idsAreShortLowerCaseAndDashed() {
		assertTrue(LayoutNames.validId("default"));
		assertTrue(LayoutNames.validId("pvp-2"));
		assertTrue(LayoutNames.validId("a".repeat(40)));
		assertFalse(LayoutNames.validId("a".repeat(41)));
		assertFalse(LayoutNames.validId(""));
		assertFalse(LayoutNames.validId(null));
		assertFalse(LayoutNames.validId("PvP"));
		assertFalse(LayoutNames.validId("my layout"));
		assertFalse(LayoutNames.validId("x.damaged-20261015-101500"));
		assertFalse(LayoutNames.validId("../x"));
	}

	@Test
	void slugsComeFromTheName() {
		assertEquals("pvp", LayoutNames.slug("PvP"));
		assertEquals("pvp-ranked", LayoutNames.slug("  PvP · Ranked!  "));
		assertEquals("creme-brulee", LayoutNames.slug("Crème brûlée"));
		assertEquals("layout", LayoutNames.slug("★★"), "nothing usable left");
		assertEquals("layout", LayoutNames.slug("---"));
		assertTrue(LayoutNames.validId(LayoutNames.slug("x".repeat(80))));
	}

	@Test
	void takenIdsGetANumber() {
		Set<String> taken = Set.of("pvp", "pvp-2");
		assertEquals("pvp-3", LayoutNames.uniqueId("PvP", taken::contains));
		assertEquals("minimal", LayoutNames.uniqueId("Minimal", taken::contains));
		String longName = "a".repeat(40);
		String id = LayoutNames.uniqueId(longName, Set.of(longName)::contains);
		assertEquals("a".repeat(38) + "-2", id, "the suffix fits within 40");
		assertTrue(LayoutNames.validId(id));
	}

	@Test
	void windowsDeviceNamesAreNeverIds() {
		Set<String> none = Set.of();
		assertEquals("con-2", LayoutNames.uniqueId("Con", none::contains));
		assertEquals("nul-2", LayoutNames.uniqueId("NUL", none::contains));
		assertEquals("aux-2", LayoutNames.uniqueId("aux", none::contains));
		assertEquals("prn-2", LayoutNames.uniqueId("Prn", none::contains));
		assertEquals("com1-2", LayoutNames.uniqueId("COM1", none::contains));
		assertEquals("lpt9-2", LayoutNames.uniqueId("lpt9", none::contains));
		assertEquals("console", LayoutNames.uniqueId("Console", none::contains), "only the exact names");
		assertEquals("com10", LayoutNames.uniqueId("com10", none::contains));
		assertTrue(LayoutNames.reservedOnWindows("con"));
		assertFalse(LayoutNames.reservedOnWindows("con-2"));
	}

	@Test
	void namesAreCheckedTrimmedAndUniqueIgnoringCase() {
		List<String> others = List.of("Balanced", "PvP");
		assertNull(LayoutNames.nameError("Ranked", others));
		assertNull(LayoutNames.nameError("  Ranked  ", others));
		assertEquals("Enter a name", LayoutNames.nameError("   ", others));
		assertEquals("Enter a name", LayoutNames.nameError("", others));
		assertEquals("Use 32 characters or fewer", LayoutNames.nameError("x".repeat(33), others));
		assertNull(LayoutNames.nameError("x".repeat(32), others));
		assertNull(LayoutNames.nameError("  " + "x".repeat(32) + "  ", others), "length counts after trimming");
		assertEquals("Another layout is already called \"PvP\"", LayoutNames.nameError("pvp", others));
		assertEquals("Another layout is already called \"Balanced\"", LayoutNames.nameError(" BALANCED ", others));
		assertNull(LayoutNames.nameError("😀".repeat(32), others), "characters, not UTF-16 units");
		assertEquals("Use 32 characters or fewer", LayoutNames.nameError("😀".repeat(33), others));
	}

	@Test
	void cleaningDropsControlCharacters() {
		assertEquals("Two lines", LayoutNames.clean(" Two\n lines\t"));
		assertEquals("Way too", LayoutNames.clean("Way\ntoo"), "a line break between words becomes a space");
		assertEquals("Way too", LayoutNames.clean("Way\r\n\ttoo"), "one space for a run");
		assertEquals("Ranked", LayoutNames.clean("\u0000Ranked\u007F"));
		assertEquals("Enter a name", LayoutNames.nameError("\n\t", List.of()));
	}

	@Test
	void uniqueNamesAreNumbered() {
		List<String> names = List.of("Balanced", "balanced 2", "PvP");
		assertEquals("Balanced 3", LayoutNames.uniqueName("Balanced", names));
		assertEquals("Minimal", LayoutNames.uniqueName("Minimal", names));
		String full = "y".repeat(32);
		assertEquals("y".repeat(30) + " 2", LayoutNames.uniqueName(full, List.of(full)), "stays within 32");
	}

	@Test
	void onboardingShowsOnlyForANewPlayer() {
		assertTrue(OnboardingDecision.shouldShow(false, false));
		assertFalse(OnboardingDecision.shouldShow(true, false), "finished or skipped");
		assertFalse(OnboardingDecision.shouldShow(false, true), "already has layouts");
		assertFalse(OnboardingDecision.shouldShow(true, true));
	}
}

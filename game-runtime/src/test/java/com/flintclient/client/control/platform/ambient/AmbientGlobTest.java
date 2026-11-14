package com.flintclient.client.control.platform.ambient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** "Silent dimensions": what the player types is matched as text with * wildcards, never compiled as a pattern. */
class AmbientGlobTest {
	@Test
	void textIsMatchedAnywhereInTheId() {
		assertTrue(AmbientGlob.contains("minecraft:the_end", "end"));
		assertTrue(AmbientGlob.contains("minecraft:the_end", "minecraft:the_end"));
		assertFalse(AmbientGlob.contains("minecraft:overworld", "nether"));
	}

	@Test
	void aStarIsAnyRun() {
		assertTrue(AmbientGlob.contains("twilightforest:twilight_forest", "twilight*forest"));
		assertTrue(AmbientGlob.contains("minecraft:the_nether", "mine*nether"));
		assertFalse(AmbientGlob.contains("minecraft:the_nether", "nether*mine"));
		assertTrue(AmbientGlob.contains("anything", "*"));
	}

	@Test
	void whatARegexWouldChokeOnIsJustText() {
		// The crash sweep's text: "{" made String.matches throw PatternSyntaxException every tick.
		assertFalse(AmbientGlob.contains("minecraft:overworld", "qa sweep ✓ 😀 %s {} \\n"));
		assertFalse(AmbientGlob.contains("minecraft:overworld", "[(?"));
		assertTrue(AmbientGlob.contains("mod:a{b}", "a{b}"));
		assertTrue(AmbientGlob.contains("mod:a.b", "a.b"));
		assertFalse(AmbientGlob.contains("mod:axb", "a.b"));
	}
}

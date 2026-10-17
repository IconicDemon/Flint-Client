package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NameMaskTest {
	private static final Map<String, String> NAMES = Map.of("Steve_01", "You", "Alex", "Player");

	@Test
	void wholeWordsAnyCase() {
		assertEquals("You joined the game", NameMask.apply("Steve_01 joined the game", NAMES));
		assertEquals("<You> hi Player!", NameMask.apply("<steve_01> hi ALEX!", NAMES));
		assertEquals("Alexander was here", NameMask.apply("Alexander was here", NAMES), "only whole words");
		assertEquals("xAlex", NameMask.apply("xAlex", NAMES));
	}

	@Test
	void untouchedTextIsTheSameString() {
		String text = "nothing to see";
		assertSame(text, NameMask.apply(text, NAMES));
		assertSame(text, NameMask.apply(text, Map.of()));
	}
}

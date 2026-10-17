package com.flintclient.client.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextEditStateTest {
	private static TextEditState typed(String text) {
		TextEditState state = new TextEditState(32);
		state.set(text);
		return state;
	}

	@Test
	void typingInsertsAtTheCaretAndReplacesTheSelection() {
		TextEditState state = typed("flint");
		state.home(false);
		state.insert("my ");
		assertEquals("my flint", state.text());
		assertEquals(3, state.caret());
		state.end(true);
		state.insert("HUD");
		assertEquals("my HUD", state.text());
		assertFalse(state.hasSelection());
	}

	@Test
	void theMaximumLengthCutsTypingAndPasting() {
		TextEditState state = new TextEditState(5);
		state.insert("abc");
		state.insert("defgh");
		assertEquals("abcde", state.text());
		state.insert("x");
		assertEquals("abcde", state.text(), "full");
		state.selectAll();
		state.insert("0123456789");
		assertEquals("01234", state.text(), "a selection frees its room first");
		state.set("too long for it");
		assertEquals("too l", state.text());
	}

	@Test
	void backspaceAndDeleteRemoveCharactersOrWords() {
		TextEditState state = typed("move the hud");
		state.backspace(false);
		assertEquals("move the hu", state.text());
		state.backspace(true);
		assertEquals("move the ", state.text());
		state.backspace(true);
		assertEquals("move ", state.text(), "spaces, then the word, as Minecraft's edit box");
		state.home(false);
		state.delete(true);
		assertEquals("", state.text());
		state.set("ab");
		state.home(false);
		state.delete(false);
		assertEquals("b", state.text());
		state.backspace(false);
		assertEquals("b", state.text(), "nothing before the caret");
	}

	@Test
	void arrowsMoveAndExtendTheSelection() {
		TextEditState state = typed("abc def");
		state.left(true, false);
		state.left(true, false);
		assertEquals("ef", state.selected());
		state.left(false, false);
		assertEquals(5, state.caret(), "collapses to the selection's start");
		state.left(true, true);
		assertEquals("d", state.selected(), "a word to the left, selecting");
		assertEquals(5, state.anchor());
		state.right(false, true);
		assertEquals(7, state.caret());
		assertFalse(state.hasSelection());
		state.home(true);
		assertEquals("abc def", state.selected());
	}

	@Test
	void selectAllAndCut() {
		TextEditState state = typed("layout");
		state.selectAll();
		assertEquals("layout", state.selected());
		assertEquals("layout", state.cut());
		assertTrue(state.isEmpty());
		assertEquals("", state.cut(), "nothing selected");
	}

	@Test
	void surrogatePairsAreNeverSplit() {
		TextEditState state = new TextEditState(2);
		state.insert("a😀b");
		assertEquals("a", state.text(), "the pair doesn't fit whole, so it is left out");
		state = new TextEditState(3);
		state.insert("a😀b");
		assertEquals("a😀", state.text());
		state = typed("a😀");
		state.backspace(false);
		assertEquals("a", state.text());
		state = typed("😀x");
		state.home(false);
		state.right(false, false);
		assertEquals(2, state.caret());
		state.moveTo(1, false);
		assertEquals(0, state.caret(), "never between the halves");
	}

	@Test
	void everyChangeMovesTheVersion() {
		TextEditState state = typed("abc");
		int version = state.version();
		state.left(false, false);
		assertNotEquals(version, state.version());
		version = state.version();
		state.home(false);
		state.home(false);
		int afterOne = state.version();
		state.home(false);
		assertEquals(afterOne, state.version(), "no change, no new version");
		assertNotEquals(version, afterOne);
	}

	@Test
	void typingIntoAFullFieldIsNoChange() {
		TextEditState state = new TextEditState(3);
		state.set("abc");
		int version = state.version();
		state.insert("d");
		state.insert("");
		assertEquals("abc", state.text());
		assertEquals(version, state.version(), "nothing typed, nothing reported");
		state.left(true, false);
		int selected = state.version();
		state.insert("x");
		assertEquals("abx", state.text(), "a selection still makes room");
		assertNotEquals(selected, state.version());
	}

	@Test
	void wordBoundariesSkipSpacesThenAWord() {
		assertEquals(4, TextEditState.wordLeft("abc def", 7));
		assertEquals(0, TextEditState.wordLeft("abc   ", 6));
		assertEquals(4, TextEditState.wordRight("abc def", 0));
		assertEquals(7, TextEditState.wordRight("abc def", 4));
	}
}

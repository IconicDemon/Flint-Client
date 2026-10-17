package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UndoStackTest {
	@Test
	void undoAndRedoWalkTheHistory() {
		UndoStack<String> stack = new UndoStack<>();
		assertFalse(stack.canUndo());
		assertNull(stack.undo("a"), "nothing to undo");
		assertNull(stack.redo("a"), "nothing to redo");
		String state = "a";
		stack.push(state);
		state = "b";
		stack.push(state);
		state = "c";
		state = stack.undo(state);
		assertEquals("b", state);
		state = stack.undo(state);
		assertEquals("a", state);
		assertFalse(stack.canUndo());
		assertEquals(2, stack.redoSize());
		state = stack.redo(state);
		assertEquals("b", state);
		state = stack.redo(state);
		assertEquals("c", state);
		assertFalse(stack.canRedo());
		assertEquals(2, stack.undoSize());
	}

	@Test
	void aNewChangeAfterUndoDropsTheRedoBranch() {
		UndoStack<Integer> stack = new UndoStack<>();
		stack.push(1);
		stack.push(2);
		int state = stack.undo(3);
		assertEquals(2, state);
		assertTrue(stack.canRedo());
		stack.push(state);
		assertFalse(stack.canRedo(), "branching clears redo");
		assertEquals(2, stack.undoSize());
	}

	@Test
	void keepsAtMostFiftyStepsDroppingTheOldest() {
		UndoStack<Integer> stack = new UndoStack<>();
		for (int i = 0; i < 60; i++) {
			stack.push(i);
		}
		assertEquals(UndoStack.CAPACITY, stack.undoSize());
		assertEquals(50, UndoStack.CAPACITY);
		Integer state = 60;
		Integer oldest = null;
		while (stack.canUndo()) {
			state = stack.undo(state);
			oldest = state;
		}
		assertEquals(10, oldest, "the first ten were dropped");
		assertEquals(50, stack.redoSize());
	}

	@Test
	void redoRespectsTheCapacityToo() {
		UndoStack<Integer> stack = new UndoStack<>(2);
		stack.push(1);
		stack.push(2);
		int state = stack.undo(3);
		state = stack.redo(state);
		assertEquals(3, state);
		assertEquals(2, stack.undoSize());
		stack.clear();
		assertFalse(stack.canUndo());
		assertFalse(stack.canRedo());
		assertThrows(IllegalArgumentException.class, () -> new UndoStack<>(0));
	}
}

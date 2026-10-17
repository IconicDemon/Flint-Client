package com.flintclient.client.control.hud;

import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;

/**
 * Undo and redo for the HUD editor, as whole snapshots. Before each change the editor {@link #push}es the
 * state it is about to change; {@link #undo} hands back the previous state and keeps the current one for
 * {@link #redo}. A new change after an undo starts a new branch, so the redo history is dropped. At most
 * {@link #CAPACITY} steps are kept; the oldest go first.
 */
public final class UndoStack<T> {
	public static final int CAPACITY = 50;

	private final int capacity;
	private final ArrayDeque<T> undo = new ArrayDeque<>();
	private final ArrayDeque<T> redo = new ArrayDeque<>();

	public UndoStack() {
		this(CAPACITY);
	}

	public UndoStack(int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacity " + capacity);
		}
		this.capacity = capacity;
	}

	/** Records {@code before}, the state just before a change. Clears the redo history. */
	public void push(T before) {
		undo.addLast(before);
		if (undo.size() > capacity) {
			undo.removeFirst();
		}
		redo.clear();
	}

	/** The state to go back to, or null when there is none; {@code current} becomes redoable. */
	public @Nullable T undo(T current) {
		T previous = undo.pollLast();
		if (previous != null) {
			redo.addLast(current);
		}
		return previous;
	}

	/** The state to go forward to, or null when there is none; {@code current} becomes undoable again. */
	public @Nullable T redo(T current) {
		T next = redo.pollLast();
		if (next != null) {
			undo.addLast(current);
			if (undo.size() > capacity) {
				undo.removeFirst();
			}
		}
		return next;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public int undoSize() {
		return undo.size();
	}

	public int redoSize() {
		return redo.size();
	}

	public void clear() {
		undo.clear();
		redo.clear();
	}
}

package com.flintclient.client.control.editor;

import com.flintclient.client.control.hud.UndoStack;
import com.flintclient.client.control.model.Layout;
import org.jspecify.annotations.Nullable;

/**
 * One visit to the HUD editor: the layout is edited in place (so the HUD shows every change at once), with a
 * copy taken at open to compare with and to go back to, and an undo history of whole-layout snapshots.
 *
 * <p>A change is a gesture: {@link #begin()} notes the layout as it is, the gesture changes the layout any
 * number of times (a drag, a slider being moved), and {@link #end()} adds one undo step if the layout really
 * differs from the noted copy. So a drag is one step, and a click that moves nothing is none.
 *
 * <p>Repeated key presses (arrow-key nudges, held down or tapped) form a <i>run</i>: {@link #continueRun}
 * keeps one gesture open while presses keep coming, so a run is one undo step rather than one per key repeat
 * (which would fill the history in seconds). The run ends when it has been idle for {@link #RUN_IDLE_NANOS}
 * ({@link #endIdleRun}) or as soon as anything else begins. Pure (no Minecraft), for tests. Render thread.
 */
public final class EditorSession {
	private final Layout layout;
	private final Layout opened;
	private final UndoStack<Layout> history = new UndoStack<>();
	/** Presses closer together than this continue one run (a held key repeats every 50 ms or so). */
	public static final long RUN_IDLE_NANOS = 750_000_000L;

	private @Nullable Layout pending;
	/** The open gesture is a run of key presses, last continued at {@link #runAt}. */
	private boolean run;
	private long runAt;

	public EditorSession(Layout layout) {
		this.layout = layout;
		this.opened = layout.copy();
	}

	public Layout layout() {
		return layout;
	}

	/**
	 * Starts a gesture (nothing happens if one is already under way). A run of key presses still open is ended
	 * first, so the new gesture is its own step.
	 */
	public void begin() {
		if (run) {
			end();
		}
		if (pending == null) {
			pending = layout.copy();
		}
	}

	/**
	 * A key press that changes the layout, at {@code nowNanos}: continues the run of presses under way, or
	 * ends any other gesture and starts a run.
	 */
	public void continueRun(long nowNanos) {
		if (!run) {
			end();
			pending = layout.copy();
			run = true;
		}
		runAt = nowNanos;
	}

	/** Whether a run of key presses is open. */
	public boolean inRun() {
		return run;
	}

	/** Ends the run of key presses if none came for {@link #RUN_IDLE_NANOS}; returns whether it ended. */
	public boolean endIdleRun(long nowNanos) {
		if (run && nowNanos - runAt >= RUN_IDLE_NANOS) {
			end();
			return true;
		}
		return false;
	}

	/** Whether a gesture is under way. */
	public boolean editing() {
		return pending != null;
	}

	/** Ends the gesture: one undo step when it changed something. */
	public void end() {
		Layout before = pending;
		pending = null;
		run = false;
		if (before != null && !layout.equals(before)) {
			history.push(before);
		}
	}

	/** {@code change} as one gesture. */
	public void edit(Runnable change) {
		begin();
		change.run();
		end();
	}

	/** Goes back one step; false when there is nothing to undo. */
	public boolean undo() {
		end();
		Layout previous = history.undo(layout.copy());
		if (previous == null) {
			return false;
		}
		layout.restore(previous);
		return true;
	}

	/** Goes forward one step; false when there is nothing to redo. */
	public boolean redo() {
		end();
		Layout next = history.redo(layout.copy());
		if (next == null) {
			return false;
		}
		layout.restore(next);
		return true;
	}

	public boolean canUndo() {
		return history.canUndo();
	}

	public boolean canRedo() {
		return history.canRedo();
	}

	/** Whether the layout differs from when the editor opened (compared with the editor's own copy, not the file). */
	public boolean dirty() {
		return !layout.equals(opened);
	}

	/** Puts the layout back as it was when the editor opened (Cancel, Discard). */
	public void discard() {
		pending = null;
		run = false;
		layout.restore(opened);
		history.clear();
	}

	/**
	 * What the layout holds now becomes where {@link #discard} goes back to, and the history starts again (the layout was
	 * saved and changed elsewhere, in the module menu, so earlier steps would undo more than the editor did).
	 */
	public void accept() {
		pending = null;
		run = false;
		opened.restore(layout);
		history.clear();
	}

	/** Every module back to its default position and size, as one step. */
	public void resetPositions() {
		edit(layout::resetPositions);
	}
}

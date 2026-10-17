package com.flintclient.client.control.model;

/**
 * The Control Center window's open and close animation as a state machine driven by frame time:
 * CLOSED → OPENING → OPEN → CLOSING → CLOSED. Opening takes {@value #OPEN_SECONDS} s (ease-out cubic, the window
 * rising {@value #OPEN_OFFSET} units into place while fading in); closing takes {@value #CLOSE_SECONDS} s (fading
 * out while sinking {@value #CLOSE_OFFSET} units). With reduced motion or animations off, both are instant.
 *
 * <p>Call {@link #advance} once per frame with the frame time; it returns true exactly once, on the frame the
 * window finishes closing, which is when the screen should actually go away. Reversing half way (closing while
 * still opening, or the other way round) carries on from the current opacity and position, so nothing jumps.
 * Allocation-free.
 */
public final class OpenState {
	public static final float OPEN_SECONDS = 0.18F;
	public static final float CLOSE_SECONDS = 0.11F;
	/** Units the window starts below its place when opening. */
	public static final float OPEN_OFFSET = 8.0F;
	/** Units the window sinks while closing. */
	public static final float CLOSE_OFFSET = 4.0F;

	public enum Phase {
		CLOSED,
		OPENING,
		OPEN,
		CLOSING
	}

	private Phase phase = Phase.CLOSED;
	private float elapsed;
	private boolean closedPending;
	/** Where the current transition started: the offset then, and the eased progress then (0 unless it reversed). */
	private float offsetFrom;
	private float easeFrom;

	public Phase phase() {
		return phase;
	}

	/** Starts opening (or opens at once when {@code instant}). Opening while closing picks up from the current fade. */
	public void open(boolean instant) {
		closedPending = false;
		if (instant) {
			phase = Phase.OPEN;
			elapsed = 0;
			return;
		}
		switch (phase) {
			case CLOSED -> start(Phase.OPENING, 0.0F, OPEN_OFFSET);
			// Continue from the same opacity and position rather than flashing back to transparent.
			case CLOSING -> start(Phase.OPENING, inverseEaseOut(alpha()), offsetY());
			case OPENING, OPEN -> {
			}
		}
	}

	/**
	 * Starts closing (or closes at once when {@code instant}); an {@link #advance} reports the end either way.
	 * Closing while opening picks up from the current fade and position. Closing a window that is already
	 * closed (one that never started opening, say) reports the end on the next {@link #advance} too, so its
	 * screen still goes away. Does nothing more when already closing.
	 */
	public void close(boolean instant) {
		if (phase == Phase.CLOSED) {
			closedPending = true;
			return;
		}
		if (instant) {
			phase = Phase.CLOSED;
			elapsed = 0;
			closedPending = true;
			return;
		}
		if (phase == Phase.CLOSING) {
			return;
		}
		// alpha = 1 - easeOut(t) while closing, so start where easeOut(t) = 1 - alpha.
		start(Phase.CLOSING, inverseEaseOut(1.0F - alpha()), offsetY());
	}

	/** Begins {@code next} at {@code progress} (0..1) with the window currently {@code offset} units low. */
	private void start(Phase next, float progress, float offset) {
		phase = next;
		elapsed = progress * (next == Phase.OPENING ? OPEN_SECONDS : CLOSE_SECONDS);
		easeFrom = easeOut(progress);
		offsetFrom = offset;
	}

	/** Moves time on; returns true on the one call where the window has just finished closing. */
	public boolean advance(float seconds) {
		if (seconds > 0 && Float.isFinite(seconds)) {
			if (phase == Phase.OPENING) {
				elapsed += seconds;
				if (elapsed >= OPEN_SECONDS) {
					phase = Phase.OPEN;
					elapsed = 0;
				}
			} else if (phase == Phase.CLOSING) {
				elapsed += seconds;
				if (elapsed >= CLOSE_SECONDS) {
					phase = Phase.CLOSED;
					elapsed = 0;
					closedPending = true;
				}
			}
		}
		if (closedPending) {
			closedPending = false;
			return true;
		}
		return false;
	}

	/** How far through the current transition, 0..1 (1 when open, 0 when closed). */
	public float progress() {
		return switch (phase) {
			case CLOSED -> 0.0F;
			case OPEN -> 1.0F;
			case OPENING -> Math.min(elapsed / OPEN_SECONDS, 1.0F);
			case CLOSING -> Math.min(elapsed / CLOSE_SECONDS, 1.0F);
		};
	}

	/** Window (and dim) opacity, 0..1. */
	public float alpha() {
		return switch (phase) {
			case CLOSED -> 0.0F;
			case OPEN -> 1.0F;
			case OPENING -> easeOut(progress());
			case CLOSING -> 1.0F - easeOut(progress());
		};
	}

	/**
	 * How far below its place to draw the window, in UI units (the caller snaps it to whole pixels): from
	 * {@value #OPEN_OFFSET} up to 0 while opening, from 0 down to {@value #CLOSE_OFFSET} while closing, and from
	 * wherever it was after a reversal.
	 */
	public float offsetY() {
		return switch (phase) {
			case CLOSED, OPEN -> 0.0F;
			case OPENING -> offsetFrom * (1.0F - travelled());
			case CLOSING -> offsetFrom + (CLOSE_OFFSET - offsetFrom) * travelled();
		};
	}

	/** The share of the current transition's eased distance covered since it started, 0..1. */
	private float travelled() {
		float left = 1.0F - easeFrom;
		return left <= 1.0E-6F ? 1.0F : Math.clamp((easeOut(progress()) - easeFrom) / left, 0.0F, 1.0F);
	}

	/** Input is taken only while opening or open; a closing window ignores it. */
	public boolean acceptsInput() {
		return phase == Phase.OPENING || phase == Phase.OPEN;
	}

	/** Ease-out cubic (the same curve as {@code ui.Motion.easeOut}, which this pure class can't import). */
	static float easeOut(float t) {
		float u = 1.0F - Math.clamp(t, 0.0F, 1.0F);
		return 1.0F - u * u * u;
	}

	private static float inverseEaseOut(float value) {
		return 1.0F - (float) Math.cbrt(1.0F - Math.clamp(value, 0.0F, 1.0F));
	}
}

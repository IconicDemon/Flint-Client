package com.flintclient.client.control.feature;

/**
 * The Stopwatch module's watch: Start/stop starts a fresh run from zero, or stops the one running (its time stays shown);
 * Pause holds a running watch and resumes it; Reset puts it back to zero, stopped. Times in milliseconds of a monotonic
 * clock. No Minecraft classes.
 */
public final class StopwatchState {
	private boolean running;
	private boolean paused;
	private long startedAt;
	private long banked;

	/** Start/stop: a stopped watch starts again from zero; a running (or paused) one stops where it is. */
	public void startStop(long nowMs) {
		if (running) {
			banked = elapsed(nowMs);
			running = false;
			paused = false;
		} else {
			banked = 0;
			startedAt = nowMs;
			running = true;
			paused = false;
		}
	}

	/** Pause: holds a running watch, or resumes a held one; nothing for a stopped watch. */
	public void pause(long nowMs) {
		if (!running) {
			return;
		}
		if (paused) {
			startedAt = nowMs;
			paused = false;
		} else {
			banked = elapsed(nowMs);
			paused = true;
		}
	}

	public void reset() {
		running = false;
		paused = false;
		banked = 0;
	}

	/** The time on the watch. */
	public long elapsed(long nowMs) {
		return running && !paused ? banked + Math.max(0, nowMs - startedAt) : banked;
	}

	public boolean running() {
		return running && !paused;
	}

	public boolean paused() {
		return running && paused;
	}
}

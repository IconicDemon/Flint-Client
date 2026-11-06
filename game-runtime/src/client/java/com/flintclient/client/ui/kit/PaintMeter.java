package com.flintclient.client.ui.kit;

import com.flintclient.client.FlintLog;

/**
 * How quickly a kit screen comes up and how much each of its frames costs, for the log: when the screen goes away, one
 * line with the time from opening to the end of its first frame's drawing, that drawing, and the slowest frame after
 * it with the count of frames over a 60 Hz frame's 16 ms (page switches and scrolling show up there). Two
 * {@code nanoTime} calls a frame and nothing allocated. Render thread.
 */
final class PaintMeter {
	/** A frame's worth of time at 60 Hz. */
	private static final long FRAME_NANOS = 16_000_000L;

	private long openedAt = -1;
	private long firstFrame = -1;
	private long firstPaint;
	private long slowest;
	private int frames;
	private int slow;

	/** The screen is being opened (made, or shown again); the first call of an opening counts. */
	void opening() {
		if (openedAt < 0) {
			openedAt = System.nanoTime();
		}
	}

	/** One frame drawn, from {@code began} to {@code ended}. */
	void painted(long began, long ended) {
		long took = ended - began;
		if (frames++ == 0) {
			firstFrame = openedAt < 0 ? took : ended - openedAt;
			firstPaint = took;
			return;
		}
		if (took > slowest) {
			slowest = took;
		}
		if (took > FRAME_NANOS) {
			slow++;
		}
	}

	/** The screen went away: one line if it drew anything, and a fresh count for its next opening. */
	void report(String screen) {
		if (frames > 0) {
			FlintLog.LOGGER.info("Flint {}: first frame {} ms after opening ({} ms drawing it); then {} frames, slowest {} ms, {} over 16 ms",
				screen, millis(firstFrame), millis(firstPaint), frames - 1, millis(slowest), slow);
		}
		openedAt = -1;
		firstFrame = -1;
		firstPaint = 0;
		slowest = 0;
		frames = 0;
		slow = 0;
	}

	private static String millis(long nanos) {
		return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
	}
}

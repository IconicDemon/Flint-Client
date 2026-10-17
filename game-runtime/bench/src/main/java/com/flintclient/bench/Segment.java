package com.flintclient.bench;

/**
 * One measured stretch of the scenario. Frames, ticks and samples are index ranges into the recorders,
 * taken on the client thread between frames, so every frame belongs to exactly one segment.
 */
final class Segment {
	final String name;
	final long startNs;
	final long durationNs;
	final int frameFrom;
	final int tickFrom;
	final int sampleFrom;
	long endNs = -1;
	int frameTo;
	int tickTo;
	int sampleTo;
	boolean complete;
	// The integrated server's ring of its last 100 tick times, copied once at the end (null outside a world).
	long[] serverTickWindowNs;

	Segment(String name, long startNs, long durationNs, int frameFrom, int tickFrom, int sampleFrom) {
		this.name = name;
		this.startNs = startNs;
		this.durationNs = durationNs;
		this.frameFrom = frameFrom;
		this.tickFrom = tickFrom;
		this.sampleFrom = sampleFrom;
	}

	/** Planned length of a segment: quick mode (harness smoke tests) divides every duration by 5. */
	static long durationNs(double seconds, boolean quick) {
		return (long) (seconds * 1e9 / (quick ? 5 : 1));
	}

	boolean isOpen() {
		return endNs < 0;
	}

	boolean isDue(long nowNs) {
		return nowNs - startNs >= durationNs;
	}

	double secondsIn(long nowNs) {
		return (nowNs - startNs) / 1e9;
	}

	/** Closes the segment; {@code complete} is false when the run stopped early inside it. */
	void end(long nowNs, int frames, int ticks, int samples, boolean finished) {
		endNs = nowNs;
		frameTo = frames;
		tickTo = ticks;
		sampleTo = samples;
		complete = finished;
	}
}

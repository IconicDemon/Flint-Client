package com.flintclient.bench;

import java.util.Arrays;

/**
 * Once-per-second samples in preallocated parallel arrays (client thread only). A value that could not be
 * read is stored as -1 (or NaN for the player position) and written as null.
 */
final class SampleLog {
	static final int UNKNOWN = -1;

	final long[] atNs;
	final long[] heapUsed;
	final long[] heapCommitted;
	final long[] nonHeapUsed;
	final int[] loadedChunks;
	final int[] renderedSections;
	final int[] compileQueue;
	final long[] serverTickNs;  // integrated server average tick time over its last 100 ticks
	final double[] playerX;
	final byte[] throttle;      // FramerateLimitTracker.FramerateThrottleReason ordinal
	final byte[] focused;       // window focus: 1 or 0 (frame-rate mods throttle unfocused windows)
	int count;
	boolean overflowed;

	SampleLog(int capacity) {
		atNs = new long[capacity];
		heapUsed = new long[capacity];
		heapCommitted = new long[capacity];
		nonHeapUsed = new long[capacity];
		loadedChunks = new int[capacity];
		renderedSections = new int[capacity];
		compileQueue = new int[capacity];
		serverTickNs = new long[capacity];
		playerX = new double[capacity];
		throttle = new byte[capacity];
		focused = new byte[capacity];
	}

	/**
	 * Claims the next slot, stamped with {@code nowNs} and every value unknown, and returns its index;
	 * returns -1 when the log is full.
	 */
	int next(long nowNs) {
		int i = count;
		if (i == atNs.length) {
			overflowed = true;
			return -1;
		}
		atNs[i] = nowNs;
		heapUsed[i] = UNKNOWN;
		heapCommitted[i] = UNKNOWN;
		nonHeapUsed[i] = UNKNOWN;
		loadedChunks[i] = UNKNOWN;
		renderedSections[i] = UNKNOWN;
		compileQueue[i] = UNKNOWN;
		serverTickNs[i] = UNKNOWN;
		playerX[i] = Double.NaN;
		throttle[i] = UNKNOWN;
		focused[i] = UNKNOWN;
		count = i + 1;
		return i;
	}

	/** True when any sample in [from, to) has a known value; used to write a whole series as null. */
	static boolean anyKnown(int[] values, int from, int to) {
		return Arrays.stream(values, from, to).anyMatch(v -> v != UNKNOWN);
	}
}

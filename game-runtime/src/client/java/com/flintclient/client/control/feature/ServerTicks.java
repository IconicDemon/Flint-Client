package com.flintclient.client.control.feature;

/**
 * The server's ticks per second, measured: the server sends its game time about once a second (every 20 of its ticks),
 * so how far that time moved against how much real time passed is its tick rate. Averaged over the last few updates and
 * capped at 20 (the most a server ticks); unknown until two updates have arrived. Reset on joining or leaving a world.
 * No Minecraft classes, so it is unit-tested as it is.
 */
public final class ServerTicks {
	/** Updates averaged: about the last eight seconds. */
	static final int WINDOW = 8;
	private final long[] gameTimes = new long[WINDOW + 1];
	private final long[] nanos = new long[WINDOW + 1];
	private int count;
	private int next;

	/** An update arrived: the server's game time {@code gameTime} at {@code nowNanos}. */
	public synchronized void record(long gameTime, long nowNanos) {
		if (count > 0) {
			int last = (next + WINDOW) % (WINDOW + 1);
			if (gameTime < gameTimes[last] || nowNanos <= nanos[last]) {
				// Time went back (a /time command doesn't touch game time, but a new world does): start again.
				count = 0;
			}
		}
		gameTimes[next] = gameTime;
		nanos[next] = nowNanos;
		next = (next + 1) % (WINDOW + 1);
		count = Math.min(count + 1, WINDOW + 1);
	}

	/** Ticks per second over the recorded window (0..20), or -1 when not known yet. */
	public synchronized double tps() {
		if (count < 2) {
			return -1;
		}
		int newest = (next + WINDOW) % (WINDOW + 1);
		int oldest = (next + WINDOW + 1 - count) % (WINDOW + 1);
		double seconds = (nanos[newest] - nanos[oldest]) / 1e9;
		if (seconds <= 0) {
			return -1;
		}
		return Math.clamp((gameTimes[newest] - gameTimes[oldest]) / seconds, 0, 20);
	}

	public synchronized void reset() {
		count = 0;
		next = 0;
	}
}

package com.flintclient.client.perf;

import com.flintclient.client.FlintClient;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Developer frame-time log ({@code dev.frameStatsLog}, which the launcher sets only when started with
 * {@code FLINT_DEV_FRAMESTATS=1}). While in a world it logs the average frame rate and the median and
 * 99th-percentile frame times once a minute. It only measures; nothing in the game changes.
 */
final class FrameStatsLogModule implements PerfModule {
	static final String ID = "dev.frameStatsLog";
	private static final long INTERVAL_NANOS = 60_000_000_000L;

	private long[] sorted = new long[0];
	private @Nullable FrameStats stats;
	private long lastLogNanos;
	private long lastLogCount;

	@Override
	public String id() {
		return ID;
	}

	/** Follows the developer flag alone, so frame times can be compared with Automatic Optimization on and off. */
	@Override
	public boolean defaultEnabled(PerfConfig config, Compat compat) {
		return config.frameStatsLog();
	}

	@Override
	public void start() {
		sorted = new long[FrameStats.CAPACITY];
	}

	@Override
	public void onWorldJoined() {
		stats = FrameStats.acquire();
		lastLogNanos = System.nanoTime();
		lastLogCount = stats.count();
	}

	@Override
	public void onWorldLeft() {
		if (stats != null) {
			stats = null;
			FrameStats.release();
		}
	}

	/** After each recorded frame: a clock read and a comparison, and a log line once a minute. */
	void afterFrame() {
		if (stats == null) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastLogNanos < INTERVAL_NANOS) {
			return;
		}
		int n = stats.sortedLast((int) Math.min(stats.count() - lastLogCount, FrameStats.CAPACITY), sorted);
		FlintClient.LOGGER.info("Frame stats, last {} frames: average {} FPS, p50 {} ms, p99 {} ms", n,
			String.format(Locale.ROOT, "%.1f", stats.averageFps(n)),
			String.format(Locale.ROOT, "%.2f", FrameStats.percentile(sorted, n, 0.5) / 1.0E6),
			String.format(Locale.ROOT, "%.2f", FrameStats.percentile(sorted, n, 0.99) / 1.0E6));
		lastLogNanos = now;
		lastLogCount = stats.count();
	}
}

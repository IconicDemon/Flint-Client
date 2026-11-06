package com.flintclient.qa.legacy;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * The 1.8.9 {@code frames SECONDS NAME [DEG_PER_S]} step, as the modern QA mod's QaFrames has it: every rendered frame's time
 * (from the start of one GameRenderer.render to the next) for SECONDS, then one "QA frames" line in the same format (average
 * FPS, 1% and 0.1% lows, median, p99, max, jitter, hitches, pauses, GC, allocation), so one parser reads every version. The
 * frame times go to {@code frames-NAME.csv} in the run folder. Dev only.
 */
public final class LegacyFrames {
	private static final long[] DELTAS = new long[400_000];
	private static final long[] AT = new long[DELTAS.length];
	private static boolean recording;
	private static long start;
	private static long last;
	private static int count;
	private static long allocStart;
	private static long gcCountStart;
	private static long gcTimeStart;

	private LegacyFrames() {
	}

	/** From the head of GameRenderer.render: one frame. */
	public static void frame() {
		if (!recording) {
			return;
		}
		long now = System.nanoTime();
		if (last != 0 && count < DELTAS.length) {
			DELTAS[count] = now - last;
			AT[count] = now - start;
			count++;
		}
		last = now;
	}

	public static boolean recording() {
		return recording;
	}

	public static void begin() {
		count = 0;
		last = 0;
		start = System.nanoTime();
		allocStart = allocated();
		gcCountStart = gc(true);
		gcTimeStart = gc(false);
		recording = true;
	}

	public static String end(String name, File out) throws Exception {
		recording = false;
		long alloc = allocated() - allocStart;
		long gcCount = gc(true) - gcCountStart;
		long gcTime = gc(false) - gcTimeStart;
		int n = count;
		if (n < 10) {
			return "frames " + name + " too few frames (" + n + ")";
		}
		long total = 0;
		for (int i = 0; i < n; i++) {
			total += DELTAS[i];
		}
		long[] sorted = Arrays.copyOf(DELTAS, n);
		Arrays.sort(sorted);
		double median = sorted[n / 2] / 1e6;
		double p99 = sorted[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
		double max = sorted[n - 1] / 1e6;
		double low1 = lowFps(sorted, n, 0.01);
		double low01 = lowFps(sorted, n, 0.001);
		double avgFps = n / (total / 1e9);
		int hitches = 0;
		int pauses = 0;
		for (int i = 0; i < n; i++) {
			double ms = DELTAS[i] / 1e6;
			if (ms > 2 * median && ms > median + 4) {
				hitches++;
			}
			if (ms > 50) {
				pauses++;
			}
		}
		double jitter = 0;
		for (int i = 1; i < n; i++) {
			jitter += Math.abs(DELTAS[i] - DELTAS[i - 1]) / 1e6;
		}
		jitter /= n - 1;
		StringBuilder csv = new StringBuilder("t_s,frame_ms\n");
		for (int i = 0; i < n; i++) {
			csv.append(String.format(Locale.ROOT, "%.5f,%.4f%n", AT[i] / 1e9, DELTAS[i] / 1e6));
		}
		Files.write(new File(out, "frames-" + name + ".csv").toPath(), csv.toString().getBytes(StandardCharsets.UTF_8));
		return String.format(Locale.ROOT,
			"frames %s n=%d avgFps=%.1f low1=%.1f low01=%.1f median=%.2fms p99=%.2fms max=%.1fms jitter=%.3fms hitches=%d pauses50=%d gc=%d gcMs=%d allocMBps=%.2f",
			name, n, avgFps, low1, low01, median, p99, max, jitter, hitches, pauses, gcCount, gcTime, alloc / 1048576.0 / (total / 1e9));
	}

	/** The frame rate of the slowest share of frames (their average time). */
	private static double lowFps(long[] sorted, int n, double share) {
		int k = Math.max(1, (int) Math.round(n * share));
		long sum = 0;
		for (int i = n - k; i < n; i++) {
			sum += sorted[i];
		}
		return k / (sum / 1e9);
	}

	private static long gc(boolean count) {
		long total = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			long v = count ? bean.getCollectionCount() : bean.getCollectionTime();
			if (v > 0) {
				total += v;
			}
		}
		return total;
	}

	/** Bytes the render thread has allocated so far (HotSpot), or 0. */
	private static long allocated() {
		try {
			return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
		} catch (Throwable e) {
			return 0;
		}
	}
}

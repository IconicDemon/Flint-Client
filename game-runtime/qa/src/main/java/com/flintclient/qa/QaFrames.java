package com.flintclient.qa;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * The {@code frames SECONDS NAME [DEG_PER_S]} step: every rendered frame's time (from the start of one {@code runTick} to
 * the next) for SECONDS, while the camera turns at DEG_PER_S as a mouse turns it ({@code player.turn}, every frame), then
 * one "QA frames" line: average FPS, 1% and 0.1% lows, the longest frame, hitches, garbage collections and what the render
 * thread allocated. The frame times go to {@code frames-NAME.csv} in the run folder. Dev only.
 */
public final class QaFrames {
	private static final long[] DELTAS = new long[400_000];
	private static final long[] AT = new long[DELTAS.length];
	private static boolean recording;
	private static long start;
	private static long last;
	private static int count;
	private static double turning;
	private static long turnLast;
	private static long allocStart;
	private static long gcCountStart;
	private static long gcTimeStart;

	private QaFrames() {
	}

	/** From the head of {@code Minecraft.runTick}: one frame. */
	public static void frame(Minecraft mc) {
		long now = System.nanoTime();
		if (turnLast != 0 && turning != 0 && mc.player != null) {
			// Entity.turn takes mouse units (0.15° each), as MouseHandler.turnPlayer passes them.
			mc.player.turn(turning * ((now - turnLast) / 1e9) / 0.15, 0);
		}
		turnLast = now;
		if (!recording) {
			return;
		}
		if (last != 0 && count < DELTAS.length) {
			DELTAS[count] = now - last;
			AT[count] = now - start;
			count++;
		}
		last = now;
	}

	/** The {@code turn DEG_PER_S} step: the camera keeps turning at this rate every frame (0 stops it). */
	public static void turn(double degreesPerSecond) {
		turning = degreesPerSecond;
	}

	public static void begin(double turn) {
		if (turn != 0) {
			turning = turn;
		}
		count = 0;
		last = 0;
		start = System.nanoTime();
		allocStart = allocated();
		gcCountStart = gc(true);
		gcTimeStart = gc(false);
		recording = true;
	}

	/** Ends the recording: the summary line (also returned) and the CSV of frame times. */
	public static String end(String name, Path out) throws Exception {
		recording = false;
		turning = 0;
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
		// 1% low: the frame rate of the slowest 1% of frames (their average time), as benchmark tools report it.
		double low1 = lowFps(sorted, n, 0.01);
		double low01 = lowFps(sorted, n, 0.001);
		double avgFps = n / (total / 1e9);
		// Hitches: a frame over twice the median and at least 4 ms longer; pauses: over 50 ms.
		int hitches = 0;
		int pauses = 0;
		StringBuilder worst = new StringBuilder();
		for (int i = 0; i < n; i++) {
			double ms = DELTAS[i] / 1e6;
			if (ms > 2 * median && ms > median + 4) {
				hitches++;
			}
			if (ms > 50) {
				pauses++;
			}
		}
		// The ten longest frames and when they came (seconds into the recording): a pause every second shows up here.
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Long.compare(DELTAS[b], DELTAS[a]));
		for (int i = 0; i < Math.min(10, n); i++) {
			worst.append(String.format(Locale.ROOT, " %.1fms@%.2fs", DELTAS[order[i]] / 1e6, AT[order[i]] / 1e9));
		}
		// Frame-to-frame jitter: the mean absolute change of the frame time (stable pacing is low).
		double jitter = 0;
		for (int i = 1; i < n; i++) {
			jitter += Math.abs(DELTAS[i] - DELTAS[i - 1]) / 1e6;
		}
		jitter /= n - 1;
		StringBuilder csv = new StringBuilder("t_s,frame_ms\n");
		for (int i = 0; i < n; i++) {
			csv.append(String.format(Locale.ROOT, "%.5f,%.4f%n", AT[i] / 1e9, DELTAS[i] / 1e6));
		}
		Files.writeString(out.resolve("frames-" + name + ".csv"), csv, StandardCharsets.UTF_8);
		return String.format(Locale.ROOT,
			"frames %s n=%d avgFps=%.1f low1=%.1f low01=%.1f median=%.2fms p99=%.2fms max=%.1fms jitter=%.3fms hitches=%d pauses50=%d gc=%d gcMs=%d "
				+ "allocMBps=%.2f worst:%s", name, n, avgFps, low1, low01, median, p99, max, jitter, hitches, pauses, gcCount, gcTime,
			alloc / 1048576.0 / (total / 1e9), worst);
	}

	private static double lowFps(long[] sorted, int n, double share) {
		int k = Math.max(1, (int) Math.round(n * share));
		long sum = 0;
		for (int i = n - k; i < n; i++) {
			sum += sorted[i];
		}
		return k / (sum / 1e9);
	}

	private static long allocated() {
		java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		if (bean instanceof com.sun.management.ThreadMXBean sun) {
			return sun.getThreadAllocatedBytes(Thread.currentThread().threadId());
		}
		return 0;
	}

	private static long gc(boolean count) {
		long sum = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			sum += Math.max(0, count ? bean.getCollectionCount() : bean.getCollectionTime());
		}
		return sum;
	}
}

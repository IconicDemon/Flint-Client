package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.FpsWindow;
import com.flintclient.client.perf.FrameStats;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The FPS module's numbers, from {@link FrameStats} (Minecraft's own per-frame sample). Recording is only on
 * while something shows the module: every draw of it (and the module list's summary) calls {@link #use}, which
 * acquires the statistics the first time, and {@link #maintain} (called from every HUD entry point and the client
 * tick) releases them once nothing has drawn it for {@link #IDLE_NANOS}, so leaving a world, switching layouts or
 * turning the module off never leaves the recording on.
 *
 * <p>The meter refreshes four times a second. Each refresh averages the frames of the last 0.5, 1 and 2 seconds
 * (one rate per {@link FpsWindow}; the frame time is its inverse), so the module can pick its window and how often
 * it shows a new number without a meter of its own. Until a refresh has seen a frame a rate is unknown (NaN), and
 * the module shows a dash rather than a made-up number.
 *
 * <p>The 1% low is the 99th-percentile frame time turned into a rate, over the last five seconds or the last 300
 * frames, whichever is more. It needs a valid window: at least {@link #LOW_MIN_NANOS} and {@link #LOW_MIN_FRAMES}
 * of samples since recording started (the module started showing), or since {@link #restartWindow} (a world join
 * or resuming from a pause). Until then it is unknown, shown as "1% low —". Render thread; allocation-free after
 * the first 1% low (one buffer of {@link FrameStats#CAPACITY} longs).
 */
final class FpsMeter {
	static final long REFRESH_NANOS = 250_000_000L;
	static final long IDLE_NANOS = 1_500_000_000L;
	/** The default window (half a second). */
	static final int AVERAGE_REFRESHES = 2;
	/** The 1% low's time window: twenty refreshes, five seconds. */
	static final int LOW_REFRESHES = 20;
	static final long LOW_MIN_NANOS = 5_000_000_000L;
	static final int LOW_MIN_FRAMES = 300;
	static final double LOW_PERCENTILE = 0.99;
	static final int UNKNOWN = -1;
	private static final FpsWindow[] WINDOWS = FpsWindow.values();

	private static @Nullable FrameStats stats;
	/** {@code FrameStats.count()} at each refresh, by refresh number modulo the length. */
	private static final long[] counts = new long[LOW_REFRESHES + 1];
	private static int refreshes;
	private static long lastUse;
	private static long lastRefresh;
	/** Average frames per second per window ({@link FpsWindow#ordinal()}), NaN while unknown. */
	private static final double[] rates = new double[WINDOWS.length];
	private static double low = Double.NaN;
	/** When the 1% low's valid window started, and the frame count then. */
	private static long windowStart;
	private static long windowStartCount;
	/** The last time a draw asked for the 1% low (it is only worked out while something shows it). */
	private static long lowWantedAt;
	private static boolean lowWanted;
	private static long @Nullable [] sorted;

	static {
		Arrays.fill(rates, Double.NaN);
	}

	private FpsMeter() {
	}

	/** The module is being drawn now: keep recording, and refresh the numbers when a refresh is due. */
	static void use(long nowNanos, boolean wantLow) {
		FrameStats current = stats;
		if (current == null) {
			current = FrameStats.acquire();
			stats = current;
			refreshes = 0;
			Arrays.fill(rates, Double.NaN);
			lowWanted = false;
			// The baseline: frames before now (another user may already be recording) aren't ours to count.
			long count = current.count();
			record(count);
			lastRefresh = nowNanos;
			restart(nowNanos, count);
		}
		lastUse = nowNanos;
		if (wantLow) {
			lowWanted = true;
			lowWantedAt = nowNanos;
		}
		if (nowNanos - lastRefresh >= REFRESH_NANOS) {
			lastRefresh = nowNanos;
			refresh(current, nowNanos);
		}
	}

	/** Stops recording once nothing has drawn the module for a while. Cheap; call it as often as convenient. */
	static void maintain(long nowNanos) {
		if (stats != null && nowNanos - lastUse > IDLE_NANOS) {
			release();
		}
	}

	/** Stops recording now (if it was on). */
	static void release() {
		if (stats != null) {
			stats = null;
			FrameStats.release();
			Arrays.fill(rates, Double.NaN);
			low = Double.NaN;
			lowWanted = false;
		}
	}

	/**
	 * Starts the 1% low's valid window again (a world was joined, or the game resumed from a pause): until it has
	 * {@link #LOW_MIN_NANOS} and {@link #LOW_MIN_FRAMES} of new samples, the low is unknown. Nothing while not recording.
	 */
	static void restartWindow(long nowNanos) {
		FrameStats current = stats;
		if (current != null) {
			restart(nowNanos, current.count());
		}
	}

	private static void restart(long nowNanos, long count) {
		windowStart = nowNanos;
		windowStartCount = count;
		low = Double.NaN;
	}

	/** Whether this holds the statistics (for tests and the editor's checks). */
	static boolean recording() {
		return stats != null;
	}

	/** Refreshes made since recording started; grows by one every {@link #REFRESH_NANOS} while drawn. */
	static int refreshes() {
		return refreshes;
	}

	/** Average frames per second over {@code window}, or NaN while unknown. */
	static double rate(FpsWindow window) {
		return rates[window.ordinal()];
	}

	/** Average frames per second over the default half second, rounded, or {@link #UNKNOWN}. */
	static int fps() {
		double rate = rates[FpsWindow.HALF_SECOND.ordinal()];
		return Double.isNaN(rate) ? UNKNOWN : (int) Math.round(rate);
	}

	/** The 1% low as a rate, or NaN while its window isn't valid yet (or nothing asked for it). */
	static double low() {
		return low;
	}

	private static void refresh(FrameStats current, long nowNanos) {
		long count = current.count();
		for (int w = 0; w < WINDOWS.length; w++) {
			long recent = framesSince(count, counts, refreshes, WINDOWS[w].refreshes());
			if (recent > 0) {
				double average = current.averageFps((int) Math.min(recent, FrameStats.CAPACITY));
				rates[w] = average > 0 ? Math.min(average, 99_999.0) : Double.NaN;
			}
		}
		if (lowWanted && nowNanos - lowWantedAt <= 2 * REFRESH_NANOS) {
			low = lowRate(current, count, nowNanos);
		} else {
			lowWanted = false;
			low = Double.NaN;
		}
		record(count);
	}

	/** The 1% low now, or NaN while the window isn't valid. */
	private static double lowRate(FrameStats current, long count, long nowNanos) {
		long sinceStart = count - windowStartCount;
		if (!lowValid(nowNanos - windowStart, sinceStart)) {
			return Double.NaN;
		}
		long frames = Math.max(framesSince(count, counts, refreshes, LOW_REFRESHES), LOW_MIN_FRAMES);
		int n = (int) Math.min(Math.min(frames, sinceStart), FrameStats.CAPACITY);
		if (sorted == null) {
			sorted = new long[FrameStats.CAPACITY];
		}
		int got = current.sortedLast(n, sorted);
		long slowest = FrameStats.percentile(sorted, got, LOW_PERCENTILE);
		return slowest > 0 ? Math.min(1.0E9 / slowest, 99_999.0) : Double.NaN;
	}

	/** Whether {@code elapsedNanos} and {@code frames} of samples make a valid 1% low window. Pure. */
	static boolean lowValid(long elapsedNanos, long frames) {
		return elapsedNanos >= LOW_MIN_NANOS && frames >= LOW_MIN_FRAMES;
	}

	private static void record(long count) {
		counts[refreshes % counts.length] = count;
		refreshes++;
	}

	/**
	 * Frames recorded since {@code back} refreshes before the one being made now ({@code done} have been made,
	 * the first being the baseline), or since the baseline when there haven't been that many yet. Pure.
	 */
	static long framesSince(long count, long[] history, int done, int back) {
		if (done <= 0) {
			return 0;
		}
		int from = Math.max(0, done - back);
		return Math.max(0, count - history[from % history.length]);
	}
}

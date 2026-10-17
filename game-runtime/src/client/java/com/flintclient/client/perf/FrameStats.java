package com.flintclient.client.perf;

import org.jspecify.annotations.Nullable;

/**
 * Recent frame times in nanoseconds, the one source for everything in Flint that shows or logs frame
 * rate. Fed from Minecraft's own per-frame sample (the full interval between frames, including frame
 * limiter and vsync waits), and only while something has called {@link #acquire()}; otherwise a frame
 * costs one field check. The ring is created by the first acquire; after that, recording and reading
 * allocate nothing. Render thread only.
 */
public final class FrameStats {
	public static final int CAPACITY = 4096;
	private static @Nullable FrameStats shared;
	private static int users;

	private final long[] ring;
	private int next;
	private long count;

	FrameStats(int capacity) {
		this.ring = new long[capacity];
	}

	/** Starts recording, or joins whoever already records, and returns the shared statistics. */
	public static FrameStats acquire() {
		if (shared == null) {
			shared = new FrameStats(CAPACITY);
		}
		if (users++ == 0) {
			shared.clear();
		}
		return shared;
	}

	/** Balances one {@link #acquire()}; recording stops once nobody needs it. */
	public static void release() {
		if (users > 0) {
			users--;
		}
	}

	/** From the frame-time hook. True when the frame was recorded (something has acquired the statistics). */
	static boolean record(long frameNanos) {
		if (users == 0 || shared == null) {
			return false;
		}
		shared.add(frameNanos);
		return true;
	}

	void add(long frameNanos) {
		ring[next] = frameNanos;
		next = next + 1 == ring.length ? 0 : next + 1;
		count++;
	}

	void clear() {
		next = 0;
		count = 0;
	}

	/** Frames recorded since recording started, including those the ring has since overwritten. */
	public long count() {
		return count;
	}

	/** How many recent frames can be read (at most the capacity). */
	public int size() {
		return (int) Math.min(count, ring.length);
	}

	/** Average frames per second over the last {@code frames} frames (fewer if fewer are recorded); 0 with none. */
	public double averageFps(int frames) {
		int n = Math.clamp(frames, 0, size());
		long total = 0;
		for (int i = 0, index = next; i < n; i++) {
			index = index == 0 ? ring.length - 1 : index - 1;
			total += ring[index];
		}
		return total <= 0 ? 0 : n * 1.0E9 / total;
	}

	/**
	 * Copies the last {@code frames} frame times (fewer if fewer are recorded or {@code into} is shorter)
	 * into the start of {@code into}, sorted ascending, and returns how many. Read them with
	 * {@link #percentile}.
	 */
	public int sortedLast(int frames, long[] into) {
		int n = Math.clamp(frames, 0, Math.min(size(), into.length));
		int start = next - n;
		if (start >= 0) {
			System.arraycopy(ring, start, into, 0, n);
		} else {
			System.arraycopy(ring, ring.length + start, into, 0, -start);
			System.arraycopy(ring, 0, into, -start, next);
		}
		heapSort(into, n);
		return n;
	}

	/** Nearest-rank percentile ({@code fraction} from 0 to 1, e.g. 0.99) of {@code sorted[0..n)}; 0 when n is 0. */
	public static long percentile(long[] sorted, int n, double fraction) {
		if (n <= 0) {
			return 0;
		}
		// The small allowance keeps products like 0.07 * 100 = 7.000000000000001 on rank 7.
		int rank = (int) Math.ceil(fraction * n - 1.0E-9);
		return sorted[Math.clamp(rank - 1, 0, n - 1)];
	}

	/** In-place heapsort of {@code values[0..n)}. Arrays.sort can allocate a merge buffer; this never does. */
	static void heapSort(long[] values, int n) {
		for (int i = n / 2 - 1; i >= 0; i--) {
			siftDown(values, i, n);
		}
		for (int end = n - 1; end > 0; end--) {
			long top = values[0];
			values[0] = values[end];
			values[end] = top;
			siftDown(values, 0, end);
		}
	}

	private static void siftDown(long[] values, int root, int n) {
		long value = values[root];
		int child;
		while ((child = 2 * root + 1) < n) {
			if (child + 1 < n && values[child + 1] > values[child]) {
				child++;
			}
			if (values[child] <= value) {
				break;
			}
			values[root] = values[child];
			root = child;
		}
		values[root] = value;
	}
}

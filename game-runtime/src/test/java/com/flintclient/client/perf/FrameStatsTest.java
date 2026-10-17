package com.flintclient.client.perf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FrameStatsTest {
	private static final long MS = 1_000_000L;

	private static FrameStats of(int capacity, long... frames) {
		FrameStats stats = new FrameStats(capacity);
		for (long frame : frames) {
			stats.add(frame);
		}
		return stats;
	}

	@Test
	void nothingRecordedReadsAsZero() {
		FrameStats stats = new FrameStats(8);
		long[] buffer = new long[8];
		assertEquals(0, stats.size());
		assertEquals(0.0, stats.averageFps(8));
		assertEquals(0, stats.sortedLast(8, buffer));
		assertEquals(0, FrameStats.percentile(buffer, 0, 0.5));
	}

	@Test
	void averageFpsCoversTheLastFramesOnly() {
		FrameStats stats = of(16, 50 * MS, 50 * MS, 10 * MS, 10 * MS, 10 * MS, 10 * MS);
		assertEquals(100.0, stats.averageFps(4), 1e-9);
		assertEquals(6 / 0.14, stats.averageFps(6), 1e-9);
		assertEquals(6 / 0.14, stats.averageFps(100), 1e-9, "never more than what's recorded");
		assertEquals(0.0, stats.averageFps(0));
	}

	@Test
	void theRingKeepsTheNewestFrames() {
		FrameStats stats = of(4, 1, 2, 3, 4, 5, 6);
		assertEquals(6, stats.count());
		assertEquals(4, stats.size());
		long[] buffer = new long[8];
		assertEquals(4, stats.sortedLast(10, buffer));
		assertArrayEquals(new long[] {3, 4, 5, 6}, Arrays.copyOf(buffer, 4));
		assertEquals(2.0E9 / 11, stats.averageFps(2), 1e-6);
	}

	@Test
	void sortedLastSortsAcrossTheWrapAndRespectsTheBuffer() {
		FrameStats stats = of(5, 50, 10, 40, 20, 30, 60);
		long[] buffer = new long[5];
		assertEquals(5, stats.sortedLast(5, buffer));
		assertArrayEquals(new long[] {10, 20, 30, 40, 60}, buffer);

		long[] small = new long[2];
		assertEquals(2, stats.sortedLast(5, small), "never more than the buffer holds");
		assertArrayEquals(new long[] {30, 60}, small, "the newest frames");
		assertEquals(3, stats.sortedLast(3, buffer));
		assertArrayEquals(new long[] {20, 30, 60}, Arrays.copyOf(buffer, 3));
	}

	@Test
	void percentilesUseNearestRank() {
		long[] values = new long[100];
		for (int i = 0; i < 100; i++) {
			values[i] = i + 1;
		}
		assertEquals(50, FrameStats.percentile(values, 100, 0.5));
		assertEquals(99, FrameStats.percentile(values, 100, 0.99));
		assertEquals(7, FrameStats.percentile(values, 100, 0.07), "0.07 * 100 isn't rounded up to 8");
		assertEquals(1, FrameStats.percentile(values, 100, 0.0));
		assertEquals(100, FrameStats.percentile(values, 100, 1.0));
		assertEquals(2, FrameStats.percentile(values, 4, 0.5));
		assertEquals(4, FrameStats.percentile(values, 4, 0.99));
		assertEquals(1, FrameStats.percentile(values, 1, 0.99));
	}

	@Test
	void heapSortMatchesArraysSort() {
		Random random = new Random(26_3L);
		for (int n : new int[] {0, 1, 2, 3, 7, 64, 1000, FrameStats.CAPACITY}) {
			long[] values = new long[n + 3];
			for (int i = 0; i < values.length; i++) {
				// Few distinct values, like a frame-capped game, plus occasional spikes.
				values[i] = i % 97 == 0 ? random.nextInt(200) * MS : 16_666_666L + random.nextInt(5);
			}
			long[] expected = values.clone();
			Arrays.sort(expected, 0, n);
			FrameStats.heapSort(values, n);
			assertArrayEquals(expected, values, "n = " + n);
		}
	}

	@Test
	void recordsOnlyWhileAcquired() {
		assertFalse(FrameStats.record(16 * MS), "off until something acquires it");
		FrameStats stats = FrameStats.acquire();
		try {
			assertEquals(0, stats.count());
			assertTrue(FrameStats.record(16 * MS));
			assertSame(stats, FrameStats.acquire(), "one shared ring");
			assertEquals(1, stats.count(), "a second user doesn't clear it");
			FrameStats.release();
			assertTrue(FrameStats.record(16 * MS), "still recording for the first user");
			assertEquals(2, stats.count());
		} finally {
			FrameStats.release();
		}
		assertFalse(FrameStats.record(16 * MS));
		FrameStats.release();
		assertFalse(FrameStats.record(16 * MS), "an extra release doesn't turn it back on");
		try {
			assertEquals(0, FrameStats.acquire().count(), "a new recording starts empty");
		} finally {
			FrameStats.release();
		}
	}
}

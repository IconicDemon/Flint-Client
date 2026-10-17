package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EffectOrderTest {
	private static int[] sorted(int[] remaining, int[] tieBreak) {
		int[] order = new int[remaining.length];
		EffectOrder.sort(remaining, tieBreak, remaining.length, order);
		return order;
	}

	@Test
	void soonestToEndComesFirst() {
		assertArrayEquals(new int[] {2, 0, 1}, sorted(new int[] {600, 1200, 40}, new int[] {1, 2, 3}));
	}

	@Test
	void effectsWithoutAnEndGoLast() {
		int infinite = EffectOrder.INFINITE;
		assertArrayEquals(new int[] {1, 3, 2, 0}, sorted(new int[] {infinite, 5, infinite, 7_000_000}, new int[] {4, 9, 2, 1}),
			"finite by time, then infinite ones by registry number");
	}

	@Test
	void equalTimesKeepAFixedOrder() {
		assertArrayEquals(new int[] {1, 2, 0}, sorted(new int[] {100, 100, 100}, new int[] {30, 10, 20}));
		// The same set in another order sorts the same way, so rows never swap while timers run together.
		assertArrayEquals(new int[] {0, 2, 1}, sorted(new int[] {100, 100, 100}, new int[] {10, 30, 20}));
	}

	@Test
	void emptyAndSingleLists() {
		int[] order = new int[4];
		EffectOrder.sort(new int[4], new int[4], 0, order);
		EffectOrder.sort(new int[] {9}, new int[] {1}, 1, order);
		assertArrayEquals(new int[] {0}, Arrays.copyOf(order, 1));
	}

	@Test
	void sortingAllocatesNothing() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		int[] remaining = new int[12];
		int[] tie = new int[12];
		int[] order = new int[12];
		long sink = rounds(remaining, tie, order, 5_000);
		long before = threads.getCurrentThreadAllocatedBytes();
		sink += rounds(remaining, tie, order, 20_000);
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink >= 0);
		assertTrue(allocated < 1024, allocated + " bytes");
	}

	private static long rounds(int[] remaining, int[] tie, int[] order, int count) {
		long sink = 0;
		for (int round = 0; round < count; round++) {
			for (int i = 0; i < remaining.length; i++) {
				remaining[i] = (round * 31 + i * 977) % 4000 - (i == 3 ? 4001 : 0);
				tie[i] = i * 7 % 12;
			}
			EffectOrder.sort(remaining, tie, remaining.length, order);
			sink += order[0];
		}
		return sink;
	}

	@Test
	void byNameIgnoresCaseAndKeepsTiesStable() {
		String[] names = {"Speed", "absorption", "Haste", "Speed", "Night Vision"};
		int[] tieBreak = {1, 22, 3, 0, 16};
		int[] order = new int[names.length];
		EffectOrder.sortByName(names, tieBreak, names.length, order);
		assertArrayEquals(new int[] {1, 2, 4, 3, 0}, order, "absorption, Haste, Night Vision, then the two Speeds by registry number");
		EffectOrder.sortByName(names, tieBreak, 1, order);
		assertEquals(0, order[0]);
	}

	@Test
	void sortingByNameAllocatesNothing() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		String[] names = {"Speed", "absorption", "Haste", "Strength", "Night Vision", "Résistance", "Jump Boost", "Regeneration"};
		int[] tieBreak = {1, 22, 3, 5, 16, 11, 8, 10};
		int[] order = new int[names.length];
		long sink = 0;
		for (int i = 0; i < 2_000; i++) {
			EffectOrder.sortByName(names, tieBreak, names.length, order);
		}
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 50_000; i++) {
			EffectOrder.sortByName(names, tieBreak, 1 + i % names.length, order);
			sink += order[0];
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink >= 0);
		assertTrue(allocated < 2048, allocated + " bytes for 50,000 sorts");
	}
}

package com.flintclient.client.control.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class CpsCounterTest {
	@Test
	void countsPressesInTheLastSecondOnly() {
		CpsCounter cps = new CpsCounter();
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, 0));
		cps.press(CpsCounter.LEFT, 0);
		cps.press(CpsCounter.LEFT, 100);
		cps.press(CpsCounter.LEFT, 999);
		assertEquals(3, cps.countLastSecond(CpsCounter.LEFT, 999));
		assertEquals(2, cps.countLastSecond(CpsCounter.LEFT, 1000), "a press exactly a second ago has dropped out");
		assertEquals(2, cps.countLastSecond(CpsCounter.LEFT, 1099));
		assertEquals(1, cps.countLastSecond(CpsCounter.LEFT, 1100));
		assertEquals(1, cps.countLastSecond(CpsCounter.LEFT, 1998));
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, 1999));
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, 60_000));
	}

	@Test
	void theButtonsCountSeparately() {
		CpsCounter cps = new CpsCounter();
		for (int i = 0; i < 7; i++) {
			cps.press(CpsCounter.LEFT, 1000 + i * 50);
		}
		cps.press(CpsCounter.RIGHT, 1200);
		cps.press(CpsCounter.RIGHT, 1300);
		assertEquals(7, cps.countLastSecond(CpsCounter.LEFT, 1400));
		assertEquals(2, cps.countLastSecond(CpsCounter.RIGHT, 1400));
		assertEquals(1, cps.countLastSecond(CpsCounter.RIGHT, 2250));
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, 2400));
	}

	@Test
	void theRingKeepsTheNewestPresses() {
		CpsCounter cps = new CpsCounter();
		// 100 presses 5 ms apart: more than the ring holds within one second.
		for (int i = 0; i < 100; i++) {
			cps.press(CpsCounter.RIGHT, 10_000 + i * 5L);
		}
		long last = 10_000 + 99 * 5L;
		assertEquals(CpsCounter.CAPACITY, cps.countLastSecond(CpsCounter.RIGHT, last), "tops out at the capacity");
		// Later on, the window still covers the 32 newest presses (155 ms apart, end to end) and no more.
		assertEquals(32, cps.countLastSecond(CpsCounter.RIGHT, last + 1000 - 32 * 5L));
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, last), "the other button's ring is untouched");
		// The ring keeps going round correctly.
		for (int i = 0; i < 3; i++) {
			cps.press(CpsCounter.RIGHT, last + 5000 + i);
		}
		assertEquals(3, cps.countLastSecond(CpsCounter.RIGHT, last + 5002));
	}

	@Test
	void clearForgetsEverything() {
		CpsCounter cps = new CpsCounter();
		cps.press(CpsCounter.LEFT, 5);
		cps.press(CpsCounter.RIGHT, 5);
		cps.clear();
		assertEquals(0, cps.countLastSecond(CpsCounter.LEFT, 5));
		assertEquals(0, cps.countLastSecond(CpsCounter.RIGHT, 5));
		cps.press(CpsCounter.LEFT, 6);
		assertEquals(1, cps.countLastSecond(CpsCounter.LEFT, 6));
	}

	@Test
	void onlyLeftAndRightAreButtons() {
		CpsCounter cps = new CpsCounter();
		assertThrows(IllegalArgumentException.class, () -> cps.press(2, 0));
		assertThrows(IllegalArgumentException.class, () -> cps.countLastSecond(-1, 0));
	}

	@Test
	void pressingAndCountingAllocateNothing() {
		java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		assumeTrue(threads instanceof com.sun.management.ThreadMXBean, "allocation counting needs HotSpot's thread bean");
		com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) threads;
		assumeTrue(hotspot.isThreadAllocatedMemorySupported() && hotspot.isThreadAllocatedMemoryEnabled());
		CpsCounter cps = new CpsCounter();
		long sink = exercise(cps, 0);
		long before = hotspot.getCurrentThreadAllocatedBytes();
		sink += exercise(cps, 1_000_000);
		long allocated = hotspot.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink > 0);
		// 200,000 calls: anything per call would be megabytes. Allow a little for the measurement itself.
		assertTrue(allocated < 4096, "allocated " + allocated + " bytes");
	}

	private static long exercise(CpsCounter cps, long start) {
		long sum = 0;
		for (int i = 0; i < 50_000; i++) {
			long now = start + i * 7L;
			cps.press(CpsCounter.LEFT, now);
			cps.press(CpsCounter.RIGHT, now);
			sum += cps.countLastSecond(CpsCounter.LEFT, now);
			sum += cps.countLastSecond(CpsCounter.RIGHT, now);
		}
		return sum;
	}
}

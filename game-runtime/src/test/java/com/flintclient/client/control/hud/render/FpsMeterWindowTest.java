package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.control.model.FpsWindow;
import com.flintclient.client.perf.FrameStats;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The FPS module's numbers from synthetic frames: each window averages its own span, the frame time is the
 * average's inverse, and the 1% low stays unknown until it has five seconds and 300 frames since recording started
 * or since the window was restarted (world join, resume). Frames are fed through {@code FrameStats.record}, which
 * records only while someone holds the statistics, as Minecraft's frame hook does.
 */
class FpsMeterWindowTest {
	private static final long MS = 1_000_000L;
	private static final long SECOND = 1_000_000_000L;
	private static final MethodHandle RECORD = recordHandle();

	private static MethodHandle recordHandle() {
		try {
			return MethodHandles.privateLookupIn(FrameStats.class, MethodHandles.lookup())
				.findStatic(FrameStats.class, "record", MethodType.methodType(boolean.class, long.class));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean frame(long nanos) {
		try {
			return (boolean) RECORD.invokeExact(nanos);
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	@BeforeEach
	@AfterEach
	void nobodyHoldsIt() {
		FpsMeter.release();
		while (frame(16 * MS)) {
			FrameStats.release();
		}
	}

	/** Frames of {@code frameNanos} for {@code duration}, the module drawn after each; returns the time at the end. */
	private static long run(long t, long frameNanos, long duration, boolean wantLow) {
		long end = t + duration;
		long now = t;
		while (now < end) {
			now += frameNanos;
			frame(frameNanos);
			FpsMeter.use(now, wantLow);
		}
		return now;
	}

	@Test
	void eachWindowAveragesItsOwnSpan() {
		long t = 7 * SECOND;
		FpsMeter.use(t, false);
		t = run(t, 10 * MS, 3 * SECOND, false);
		t = run(t, 20 * MS, 750 * MS, false);
		assertEquals(50.0, FpsMeter.rate(FpsWindow.HALF_SECOND), 0.5, "the last half second was all 50 FPS");
		double second = FpsMeter.rate(FpsWindow.ONE_SECOND);
		double two = FpsMeter.rate(FpsWindow.TWO_SECONDS);
		// Refreshes land on frames: the last was at 10.52 s, so the windows reach back to 10.0, 9.5 and 8.5 s.
		assertEquals(76 / 1.02, second, 0.5, "half of the last second still at 100 FPS");
		assertEquals(176 / 2.02, two, 0.5, "most of the last two seconds at 100 FPS");
		assertEquals(50, FpsMeter.fps());
		assertEquals(20.0, 1000.0 / FpsMeter.rate(FpsWindow.HALF_SECOND), 0.2, "the frame time is the average's inverse");
		assertTrue(FpsMeter.refreshes() >= 14, "four refreshes a second");
	}

	@Test
	void theLowNeedsFiveSecondsAndThreeHundredFrames() {
		long t = 3 * SECOND;
		FpsMeter.use(t, true);
		// 100 FPS with every 50th frame a 40 ms hitch: 2 % of frames run at 25 FPS, so the 1% low is 25.
		for (int i = 0; i < 9; i++) {
			t = hitchyFrames(t, 50);
		}
		assertTrue(Double.isNaN(FpsMeter.low()), "4.77 s: not measured yet");
		t = hitchyFrames(t, 50);
		assertEquals(25.0, FpsMeter.low(), 0.01, "5.3 s and 500 frames");
	}

	/** 49 frames of 10 ms and one of 40 ms (for {@code every} = 50), the module drawn after each; returns the time. */
	private static long hitchyFrames(long t, int every) {
		long now = t;
		for (int i = 1; i <= every; i++) {
			long frame = i == every ? 40 * MS : 10 * MS;
			now += frame;
			frame(frame);
			FpsMeter.use(now, true);
		}
		return now;
	}

	@Test
	void aSlowGameWaitsForThreeHundredFrames() {
		long t = 11 * SECOND;
		FpsMeter.use(t, true);
		t = run(t, 33 * MS, 6 * SECOND, true);
		assertTrue(Double.isNaN(FpsMeter.low()), "six seconds at 30 FPS is under 300 frames");
		t = run(t, 33 * MS, 4_500 * MS, true);
		assertEquals(1000.0 / 33.0, FpsMeter.low(), 0.1, "over 300 frames now");
	}

	@Test
	void joiningAWorldOrResumingStartsTheWindowAgain() {
		long t = 2 * SECOND;
		FpsMeter.use(t, true);
		t = run(t, 10 * MS, 6 * SECOND, true);
		assertFalse(Double.isNaN(FpsMeter.low()));
		FpsMeter.restartWindow(t);
		assertTrue(Double.isNaN(FpsMeter.low()), "unknown at once");
		t = run(t, 10 * MS, 4 * SECOND, true);
		assertTrue(Double.isNaN(FpsMeter.low()), "still under five seconds since the restart");
		t = run(t, 10 * MS, 1_500 * MS, true);
		assertEquals(100.0, FpsMeter.low(), 0.01);
		assertFalse(Double.isNaN(FpsMeter.rate(FpsWindow.HALF_SECOND)), "the average never waits");
	}

	@Test
	void theLowIsOnlyWorkedOutWhileShown() {
		long t = SECOND;
		FpsMeter.use(t, false);
		t = run(t, 10 * MS, 6 * SECOND, false);
		assertTrue(Double.isNaN(FpsMeter.low()));
		t = run(t, 10 * MS, 600 * MS, true);
		assertEquals(100.0, FpsMeter.low(), 0.01, "the window counts from the start, not from when it was asked for");
		run(t, 10 * MS, 1 * SECOND, false);
		assertTrue(Double.isNaN(FpsMeter.low()), "dropped again once nothing shows it");
	}

	@Test
	void validityIsFiveSecondsAndThreeHundredFrames() {
		assertFalse(FpsMeter.lowValid(5 * SECOND - 1, 1000));
		assertFalse(FpsMeter.lowValid(60 * SECOND, 299));
		assertTrue(FpsMeter.lowValid(5 * SECOND, 300));
	}

	@Test
	void drawingEveryFrameAllocatesNothing() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
		long t = SECOND;
		FpsMeter.use(t, true);
		t = run(t, 10 * MS, 7 * SECOND, true);
		long sink = 0;
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 50_000; i++) {
			t += 10 * MS;
			frame(10 * MS);
			FpsMeter.use(t, (i & 1) == 0);
			FpsMeter.maintain(t);
			sink += FpsMeter.refreshes() + (long) FpsMeter.rate(FpsWindow.TWO_SECONDS) + (Double.isNaN(FpsMeter.low()) ? 0 : 1);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(sink > 0);
		assertTrue(allocated < 4096, allocated + " bytes for 50,000 frames of the meter, with the 1% low");
	}
}

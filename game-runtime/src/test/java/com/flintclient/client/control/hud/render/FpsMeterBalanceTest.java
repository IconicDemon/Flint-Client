package com.flintclient.client.control.hud.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.perf.FrameStats;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The FPS module holds Minecraft's frame-time recording only while it is drawn: one acquire however often it
 * is drawn, released once it hasn't been for {@link FpsMeter#IDLE_NANOS}, and a fresh start when it comes back.
 * Whether anyone holds the recording is read from {@code FrameStats.record}, which records only then.
 */
class FpsMeterBalanceTest {
	private static boolean recordingOn() throws ReflectiveOperationException {
		Method record = FrameStats.class.getDeclaredMethod("record", long.class);
		record.setAccessible(true);
		return (boolean) record.invoke(null, 16_000_000L);
	}

	@BeforeEach
	@AfterEach
	void nobodyHoldsIt() throws ReflectiveOperationException {
		FpsMeter.release();
		while (recordingOn()) {
			FrameStats.release();
		}
	}

	@Test
	void drawingManyTimesAcquiresOnceAndIdleReleases() throws ReflectiveOperationException {
		long t = 5_000_000_000L;
		assertFalse(FpsMeter.recording());
		for (int i = 0; i < 100; i++) {
			FpsMeter.use(t, false);
			FpsMeter.maintain(t);
			t += 16_000_000L;
		}
		assertTrue(FpsMeter.recording());
		assertTrue(recordingOn());
		FpsMeter.maintain(t + FpsMeter.IDLE_NANOS - 20_000_000L);
		assertTrue(FpsMeter.recording(), "not idle for long enough yet");
		FpsMeter.maintain(t + FpsMeter.IDLE_NANOS + 1);
		assertFalse(FpsMeter.recording());
		assertFalse(recordingOn(), "one release balanced the one acquire: nothing records any more");
	}

	@Test
	void anotherHolderKeepsRecordingAfterTheModuleLetsGo() throws ReflectiveOperationException {
		FrameStats.acquire();
		try {
			FpsMeter.use(0, false);
			FpsMeter.release();
			assertTrue(recordingOn(), "the other holder still records");
		} finally {
			FrameStats.release();
		}
		assertFalse(recordingOn());
	}

	@Test
	void comingBackStartsFreshWithoutANumber() {
		FpsMeter.use(0, false);
		FpsMeter.maintain(FpsMeter.IDLE_NANOS + 1);
		assertFalse(FpsMeter.recording());
		FpsMeter.use(FpsMeter.IDLE_NANOS + 2, false);
		assertTrue(FpsMeter.recording());
		assertEquals(FpsMeter.UNKNOWN, FpsMeter.fps(), "no made-up rate before a refresh has seen frames");
		FpsMeter.release();
		FpsMeter.release();
		assertFalse(FpsMeter.recording(), "releasing twice is harmless");
	}
}

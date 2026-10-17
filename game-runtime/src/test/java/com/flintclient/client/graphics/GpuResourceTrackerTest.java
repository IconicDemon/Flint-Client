package com.flintclient.client.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flintclient.client.graphics.GpuResourceTracker.Kind;
import org.junit.jupiter.api.Test;

class GpuResourceTrackerTest {
	@Test
	void countsAndBytesAreKeptPerKind() {
		GpuResourceTracker tracker = new GpuResourceTracker();
		tracker.register(Kind.ATLAS, GpuResourceTracker.rgba8(256, 64));
		tracker.register(Kind.ATLAS, GpuResourceTracker.rgba8(256, 128));
		tracker.register(Kind.SCREENSHOT, 1000);
		assertEquals(2, tracker.count(Kind.ATLAS));
		assertEquals(256 * 64 * 4 + 256 * 128 * 4, tracker.bytes(Kind.ATLAS));
		assertEquals(0, tracker.count(Kind.BACKDROP));
		assertEquals(1000, tracker.bytes(Kind.SCREENSHOT));

		tracker.release(Kind.ATLAS, GpuResourceTracker.rgba8(256, 64));
		assertEquals(1, tracker.count(Kind.ATLAS));
		assertEquals(256 * 128 * 4, tracker.bytes(Kind.ATLAS));
		tracker.release(Kind.ATLAS, GpuResourceTracker.rgba8(256, 128));
		tracker.release(Kind.SCREENSHOT, 1000);
		assertEquals(0, tracker.count(Kind.ATLAS));
		assertEquals(0, tracker.bytes(Kind.ATLAS));
		assertEquals(0, tracker.unbalanced());
	}

	@Test
	void theBackdropIsAboutSixMegabytes() {
		assertEquals(5_760_000, GpuResourceTracker.rgba8(1600, 900));
	}

	@Test
	void releasingWhatWasNeverRegisteredIsCountedNotGoneNegative() {
		GpuResourceTracker tracker = new GpuResourceTracker();
		tracker.release(Kind.BACKDROP, 10);
		assertEquals(0, tracker.count(Kind.BACKDROP));
		assertEquals(0, tracker.bytes(Kind.BACKDROP));
		assertEquals(1, tracker.unbalanced());

		tracker.register(Kind.ATLAS, 100);
		tracker.release(Kind.ATLAS, 60);
		assertEquals(2, tracker.unbalanced(), "bytes left after the last one went");
		assertEquals(0, tracker.bytes(Kind.ATLAS));

		tracker.register(Kind.ATLAS, 10);
		tracker.register(Kind.ATLAS, 10);
		tracker.release(Kind.ATLAS, 50);
		assertEquals(3, tracker.unbalanced(), "more bytes released than were alive");
		assertEquals(1, tracker.count(Kind.ATLAS));
		assertEquals(0, tracker.bytes(Kind.ATLAS));
	}

	@Test
	void describesEveryKindOrOnlyTheLiveOnes() {
		GpuResourceTracker tracker = new GpuResourceTracker();
		assertEquals("shape atlas 0, backdrop 0, screenshots 0, module preview 0", tracker.describe());
		assertEquals("", tracker.describeLive());

		tracker.register(Kind.ATLAS, 262_144);
		tracker.register(Kind.SCREENSHOT, 100);
		tracker.register(Kind.SCREENSHOT, 50);
		assertEquals("shape atlas 1 (262144 bytes), backdrop 0, screenshots 2 (150 bytes), module preview 0", tracker.describe());
		assertEquals("shape atlas 1 (262144 bytes), screenshots 2 (150 bytes)", tracker.describeLive());

		tracker.release(Kind.BACKDROP, 1);
		assertEquals("shape atlas 1 (262144 bytes), backdrop 0, screenshots 2 (150 bytes), module preview 0, unbalanced releases 1", tracker.describe());
	}
}

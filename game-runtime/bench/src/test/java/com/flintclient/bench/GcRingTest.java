package com.flintclient.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class GcRingTest {
	@Test
	void keepsEventsInOrderBeforeWrapping() {
		GcRing ring = new GcRing(4);
		ring.add(10, 1, "G1 Young Generation", "end of minor GC", "G1 Evacuation Pause", 3);
		ring.add(20, 2, "G1 Concurrent GC", "end of concurrent GC pause", "G1 Remark", 1);
		List<GcRing.Event> events = ring.snapshot();
		assertEquals(2, events.size());
		assertEquals(new GcRing.Event(10, 1, "G1 Young Generation", "end of minor GC", "G1 Evacuation Pause", 3), events.get(0));
		assertEquals("G1 Remark", events.get(1).cause());
		assertEquals(0, ring.dropped());
	}

	@Test
	void dropsTheOldestWhenFull() {
		GcRing ring = new GcRing(3);
		for (int i = 1; i <= 7; i++) {
			ring.add(i, i, "gc", "a", "c", i);
		}
		List<GcRing.Event> events = ring.snapshot();
		assertEquals(List.of(5L, 6L, 7L), events.stream().map(GcRing.Event::tNs).toList());
		assertEquals(4, ring.dropped());
	}

	@Test
	void acceptsEventsFromAnotherThread() throws InterruptedException {
		GcRing ring = new GcRing(1_000);
		CountDownLatch done = new CountDownLatch(1);
		Thread notifier = new Thread(() -> {
			for (int i = 0; i < 500; i++) {
				ring.add(i, i, "gc", "a", "c", 0);
			}
			done.countDown();
		});
		notifier.start();
		// Reads during writes must never see a half-written event (every event was added with tNs == uptimeMs).
		for (int i = 0; i < 100; i++) {
			for (GcRing.Event event : ring.snapshot()) {
				assertEquals(event.tNs(), event.uptimeMs());
			}
		}
		done.await();
		assertEquals(500, ring.snapshot().size());
	}
}

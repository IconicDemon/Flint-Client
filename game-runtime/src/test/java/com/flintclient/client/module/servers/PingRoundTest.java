package com.flintclient.client.module.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PingRoundTest {
	/** Stands in for ServerStatusPinger's connection list: open() adds one, closeAll() closes them all. */
	private static final class Connections {
		final List<boolean[]> list = Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger closeCalls = new AtomicInteger();

		void open() {
			list.add(new boolean[] {true});
		}

		void closeAll() {
			closeCalls.incrementAndGet();
			synchronized (list) {
				list.forEach(connection -> connection[0] = false);
			}
		}

		long stillOpen() {
			synchronized (list) {
				return list.stream().filter(connection -> connection[0]).count();
			}
		}
	}

	@Test
	void pingsRunWhileTheMenuIsOpen() {
		Connections connections = new Connections();
		PingRound round = new PingRound(connections::closeAll);
		AtomicInteger ran = new AtomicInteger();
		round.task(ran::incrementAndGet).run();
		assertEquals(1, ran.get());
		assertTrue(round.open(), "its results are recorded");
		assertEquals(0, connections.closeCalls.get());
	}

	@Test
	void queuedPingsNeverStartOnceTheMenuCloses() {
		Connections connections = new Connections();
		PingRound round = new PingRound(connections::closeAll);
		AtomicInteger ran = new AtomicInteger();
		List<Runnable> queue = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			queue.add(round.task(() -> {
				ran.incrementAndGet();
				connections.open();
			}));
		}
		queue.getFirst().run();
		round.stop();
		queue.forEach(Runnable::run);
		assertEquals(1, ran.get(), "only the ping that ran before stop()");
		assertEquals(0, connections.stillOpen());
		assertFalse(round.open(), "late results are discarded");
	}

	@Test
	void aConnectionOpenedAfterStopIsClosed() {
		Connections connections = new Connections();
		PingRound round = new PingRound(connections::closeAll);
		// stop() lands while the ping is still connecting, so its connection is added after stop() closed the others.
		round.task(() -> {
			round.stop();
			connections.open();
		}).run();
		assertEquals(0, connections.stillOpen());
		assertEquals(2, connections.closeCalls.get());
	}

	@Test
	void stoppingFromTheRenderThreadWhileAPingConnectsLeavesNothingOpen() throws InterruptedException {
		Connections connections = new Connections();
		PingRound round = new PingRound(connections::closeAll);
		ExecutorService pingThread = Executors.newSingleThreadExecutor();
		CountDownLatch connecting = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		AtomicInteger started = new AtomicInteger();
		try {
			pingThread.execute(round.task(() -> {
				started.incrementAndGet();
				connecting.countDown();
				try {
					stopped.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				connections.open();
			}));
			pingThread.execute(round.task(() -> {
				started.incrementAndGet();
				connections.open();
			}));
			assertTrue(connecting.await(5, TimeUnit.SECONDS));
			round.stop();
			stopped.countDown();
		} finally {
			pingThread.shutdown();
			assertTrue(pingThread.awaitTermination(5, TimeUnit.SECONDS));
		}
		assertEquals(1, started.get(), "the queued ping never started");
		assertEquals(0, connections.stillOpen());
	}
}

package com.flintclient.client.module.servers;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.servers.ServerShortcuts.Status;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The shortcut status checks with fake pings that block like a connect to a dead host. */
class StatusChecksTest {
	private final AtomicLong clock = new AtomicLong(1_000_000);
	/** Stands in for Minecraft.execute: what pool and network threads hand to the main thread. */
	private final LinkedBlockingQueue<Runnable> mainThread = new LinkedBlockingQueue<>();
	/** Lets every blocked fake ping return. */
	private final CountDownLatch release = new CountDownLatch(1);
	private final List<ThreadPoolExecutor> pools = new ArrayList<>();

	@AfterEach
	void stopPools() throws InterruptedException {
		release.countDown();
		for (ThreadPoolExecutor pool : pools) {
			pool.shutdownNow();
			assertTrue(pool.awaitTermination(5, SECONDS));
		}
	}

	private StatusChecks checks(ThreadPoolExecutor pool) {
		pools.add(pool);
		return new StatusChecks(pool, mainThread::add, clock::get);
	}

	private void runMainThread() {
		for (Runnable task = mainThread.poll(); task != null; task = mainThread.poll()) {
			task.run();
		}
	}

	private void runNextOnMainThread() throws InterruptedException {
		Runnable task = mainThread.poll(5, SECONDS);
		assertNotNull(task, "a ping reported back");
		task.run();
	}

	@Test
	void fivePingsRunAtOnceAndTheRestWaitForAThread() throws InterruptedException {
		ThreadPoolExecutor pool = PingPool.create(QuickPlay.MAX, 60_000);
		StatusChecks checks = checks(pool);
		PingRound round = new PingRound(() -> {
		});
		AtomicInteger running = new AtomicInteger();
		AtomicInteger mostAtOnce = new AtomicInteger();
		CountDownLatch fiveStarted = new CountDownLatch(5);
		for (int i = 0; i < 7; i++) {
			checks.ping("server" + i, round, onReply -> {
				mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
				fiveStarted.countDown();
				try {
					release.await();
				} finally {
					running.decrementAndGet();
				}
				return true;
			});
		}
		assertTrue(fiveStarted.await(5, SECONDS), "five dead hosts are tried at once, not one after another");
		assertEquals(2, pool.getQueue().size(), "the other two wait for a thread");
		release.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, SECONDS));
		assertEquals(5, mostAtOnce.get());
		assertEquals(5, pool.getLargestPoolSize(), "never more than five threads");
	}

	@Test
	void poolThreadsAreDaemonsAndExitWhenIdle() throws InterruptedException {
		ThreadPoolExecutor pool = PingPool.create(QuickPlay.MAX, 50);
		pools.add(pool);
		AtomicReference<Thread> worker = new AtomicReference<>();
		CountDownLatch ran = new CountDownLatch(1);
		pool.execute(() -> {
			worker.set(Thread.currentThread());
			ran.countDown();
		});
		assertTrue(ran.await(5, SECONDS));
		Thread thread = worker.get();
		assertTrue(thread.isDaemon(), "never keeps the game from exiting");
		assertTrue(thread.getName().startsWith("Flint server status"));
		thread.join(5_000);
		assertFalse(thread.isAlive(), "no thread stays once the pings are done");
		assertEquals(0, pool.getPoolSize());
	}

	@Test
	void theTimeoutCountsFromWhenThePingStartsNotWhenItWasQueued() throws InterruptedException {
		StatusChecks checks = checks(PingPool.create(QuickPlay.MAX, 60_000));
		// The last menu visit left five pings connecting to dead hosts; stopping it can't interrupt them.
		PingRound previous = new PingRound(() -> {
		});
		CountDownLatch stuck = new CountDownLatch(5);
		CountDownLatch freeOne = new CountDownLatch(1);
		for (int i = 0; i < 5; i++) {
			CountDownLatch until = i == 0 ? freeOne : release;
			checks.ping("dead" + i, previous, onReply -> {
				stuck.countDown();
				until.await();
				return true;
			});
		}
		assertTrue(stuck.await(5, SECONDS));
		previous.stop();
		checks.dropUnanswered();

		PingRound round = new PingRound(() -> {
		});
		CountDownLatch started = new CountDownLatch(1);
		checks.ping("example", round, onReply -> {
			started.countDown();
			release.await();
			return true;
		});
		clock.addAndGet(20_000);
		runMainThread();
		checks.expire();
		assertEquals(Status.CHECKING, checks.status("example"), "queued but never tried, so not offline");
		assertFalse(checks.due("example"), "a resize doesn't queue it twice");

		freeOne.countDown();
		assertTrue(started.await(5, SECONDS));
		runMainThread();
		clock.addAndGet(StatusChecks.TIMEOUT_MILLIS);
		checks.expire();
		assertEquals(Status.CHECKING, checks.status("example"), "15 s after it started, not after it was queued");
		clock.addAndGet(1);
		checks.expire();
		assertEquals(Status.OFFLINE, checks.status("example"));
	}

	@Test
	void anAddressThatDoesNotResolveIsOfflineAtOnce() throws InterruptedException {
		StatusChecks checks = checks(PingPool.create(QuickPlay.MAX, 60_000));
		PingRound round = new PingRound(() -> {
		});
		checks.ping("nowhere.invalid", round, onReply -> false);
		runNextOnMainThread();
		runNextOnMainThread();
		assertEquals(Status.OFFLINE, checks.status("nowhere.invalid"), "without waiting for the timeout");
	}

	@Test
	void aConnectionThatFailsIsOfflineAtOnce() throws InterruptedException {
		StatusChecks checks = checks(PingPool.create(QuickPlay.MAX, 60_000));
		PingRound round = new PingRound(() -> {
		});
		checks.ping("refused", round, onReply -> {
			throw new ConnectException("Connection refused");
		});
		runNextOnMainThread();
		runNextOnMainThread();
		assertEquals(Status.OFFLINE, checks.status("refused"));
	}

	@Test
	void anAnswerIsShownWhileItIsAFewMinutesOld() throws InterruptedException {
		StatusChecks checks = checks(PingPool.create(QuickPlay.MAX, 60_000));
		PingRound round = new PingRound(() -> {
		});
		checks.ping("example", round, onReply -> {
			// The answer arrives later, on a network thread.
			new Thread(() -> onReply.accept(new StatusChecks.Result(Status.ONLINE, 42, "26.3"))).start();
			return true;
		});
		runNextOnMainThread();
		runNextOnMainThread();
		assertEquals(Status.ONLINE, checks.status("example"));
		assertEquals(42, checks.pingMillis("example"));
		assertEquals("26.3", checks.version("example"));

		round.stop();
		checks.dropUnanswered();
		assertEquals(Status.ONLINE, checks.status("example"), "closing the menu keeps answers already received");
		assertFalse(checks.due("example"), "checked less than a minute ago");
		clock.addAndGet(StatusChecks.RECHECK_MILLIS);
		assertTrue(checks.due("example"));
		clock.addAndGet(StatusChecks.FRESH_MILLIS - StatusChecks.RECHECK_MILLIS + 1);
		assertEquals(Status.UNKNOWN, checks.status("example"), "never assumed online from an old answer");
	}

	@Test
	void anEarlierVisitsStartAndAnswerDontTouchTheNextVisitsCheck() throws InterruptedException {
		ThreadPoolExecutor pool = PingPool.create(1, 60_000);
		StatusChecks checks = checks(pool);
		PingRound previous = new PingRound(() -> {
		});
		AtomicReference<Consumer<StatusChecks.Result>> lateReply = new AtomicReference<>();
		CountDownLatch connected = new CountDownLatch(1);
		checks.ping("example", previous, onReply -> {
			lateReply.set(onReply);
			connected.countDown();
			return true;
		});
		assertTrue(connected.await(5, SECONDS));
		// The menu closes and reopens before the main thread has seen that ping start.
		previous.stop();
		checks.dropUnanswered();

		PingRound round = new PingRound(() -> {
		});
		CountDownLatch busy = new CountDownLatch(1);
		checks.ping("other", round, onReply -> {
			busy.countDown();
			release.await();
			return true;
		});
		assertTrue(busy.await(5, SECONDS));
		checks.ping("example", round, onReply -> true);
		assertEquals(1, pool.getQueue().size(), "this visit's ping of the same server waits for the thread");
		lateReply.get().accept(new StatusChecks.Result(Status.ONLINE, 5, "26.3"));
		runMainThread();
		assertEquals(Status.CHECKING, checks.status("example"), "the earlier visit's answer was dropped");

		clock.addAndGet(StatusChecks.TIMEOUT_MILLIS + 1);
		checks.expire();
		assertEquals(Status.OFFLINE, checks.status("other"));
		assertEquals(Status.CHECKING, checks.status("example"), "the earlier visit's start didn't start this check's timeout");
	}

	@Test
	void closingTheMenuClosesLateConnectionsAndDropsTheirAnswers() throws InterruptedException {
		ThreadPoolExecutor pool = PingPool.create(1, 60_000);
		StatusChecks checks = checks(pool);
		AtomicInteger openConnections = new AtomicInteger();
		PingRound round = new PingRound(() -> openConnections.set(0));
		AtomicInteger started = new AtomicInteger();
		CountDownLatch connecting = new CountDownLatch(1);
		checks.ping("slow", round, onReply -> {
			started.incrementAndGet();
			connecting.countDown();
			release.await();
			// The connect finishes after the menu has closed, and the server answers at once.
			openConnections.incrementAndGet();
			onReply.accept(new StatusChecks.Result(Status.ONLINE, 5, "26.3"));
			return true;
		});
		checks.ping("queued", round, onReply -> {
			started.incrementAndGet();
			openConnections.incrementAndGet();
			return true;
		});
		assertTrue(connecting.await(5, SECONDS));
		round.stop();
		checks.dropUnanswered();
		release.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, SECONDS));
		runMainThread();
		assertEquals(1, started.get(), "the queued ping never started");
		assertEquals(0, openConnections.get(), "the late connection was closed");
		assertEquals(Status.UNKNOWN, checks.status("slow"), "its late answer was dropped");
		assertEquals(Status.UNKNOWN, checks.status("queued"));
	}
}

package com.flintclient.client.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.graphics.Preparations.Outcome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PreparationsTest {
	/** Something made off the render thread; counts how often it's closed. */
	private static final class Made implements AutoCloseable {
		final AtomicInteger closes = new AtomicInteger();

		@Override
		public void close() {
			closes.incrementAndGet();
		}
	}

	/** A task that counts its runs and keeps what it made. With a gate, it waits for it after starting. */
	private static final class Task implements Callable<Made> {
		final AtomicInteger runs = new AtomicInteger();
		final List<Made> made = new ArrayList<>();
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch gate;

		Task(boolean gated) {
			gate = new CountDownLatch(gated ? 1 : 0);
		}

		@Override
		public Made call() throws InterruptedException {
			runs.incrementAndGet();
			started.countDown();
			gate.await();
			Made thing = new Made();
			synchronized (made) {
				made.add(thing);
			}
			return thing;
		}

		Made only() {
			synchronized (made) {
				assertEquals(1, made.size(), "made exactly once");
				return made.getFirst();
			}
		}
	}

	/** A worker pool that hasn't got round to its jobs until told to. */
	private static final class Queue implements Executor {
		final List<Runnable> queued = new ArrayList<>();

		@Override
		public void execute(Runnable job) {
			queued.add(job);
		}

		void runAll() {
			List<Runnable> jobs = List.copyOf(queued);
			queued.clear();
			jobs.forEach(Runnable::run);
		}
	}

	/** Runs each job on a new thread at once (a worker that starts straight away). */
	private static final class Workers implements Executor {
		final List<Thread> threads = new ArrayList<>();

		@Override
		public void execute(Runnable job) {
			Thread thread = new Thread(job, "test worker");
			threads.add(thread);
			thread.start();
		}

		void join() throws InterruptedException {
			for (Thread thread : threads) {
				thread.join(10_000);
				assertFalse(thread.isAlive(), "worker finished");
			}
		}
	}

	/** Waits until {@code thread} is parked (blocked waiting for something), or fails after ten seconds. */
	private static void awaitParked(Thread thread) {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
			assertTrue(thread.isAlive() && System.nanoTime() < deadline, "the thread never blocked");
			Thread.onSpinWait();
		}
	}

	@Test
	void nothingPreparedGivesNothing() {
		Preparations<String, Made> prepared = new Preparations<>();
		assertEquals(0, prepared.scale());
		assertNull(prepared.take("label_2"));
		assertEquals(Outcome.NONE, prepared.lastOutcome());
	}

	@Test
	void aFinishedResultIsHandedOverOnceAndBelongsToTheTaker() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task task = new Task(false);
		assertTrue(prepared.switchTo(2));
		prepared.start("label_2", task, workers);
		assertEquals(1, prepared.size(), "pending");
		workers.runAll();
		assertEquals(1, task.runs.get());

		assertSame(task.only(), prepared.take("label_2"));
		assertEquals(Outcome.READY, prepared.lastOutcome());
		assertNull(prepared.take("label_2"), "used up");
		assertEquals(Outcome.NONE, prepared.lastOutcome());

		prepared.expire();
		prepared.shutdown();
		assertEquals(0, task.only().closes.get(), "the taker closes it, never the preparations");
	}

	@Test
	void askingBeforeAnyWorkerStartedMakesItRightThereOnce() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task task = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", task, workers);

		Made made = prepared.take("label_2");
		assertSame(task.only(), made);
		assertEquals(Outcome.MADE_HERE, prepared.lastOutcome());
		workers.runAll();
		assertEquals(1, task.runs.get(), "the queued job finds it claimed and does nothing");
	}

	@Test
	void aJobAWorkerIsOnIsWaitedFor() throws InterruptedException {
		Preparations<String, Made> prepared = new Preparations<>();
		Workers workers = new Workers();
		Task task = new Task(true);
		prepared.switchTo(2);
		prepared.start("label_2", task, workers);
		task.started.await();

		AtomicReference<Made> taken = new AtomicReference<>();
		Thread render = new Thread(() -> taken.set(prepared.take("label_2")), "test render thread");
		render.start();
		awaitParked(render);
		assertNull(taken.get(), "not handed over while the worker is still on it");
		task.gate.countDown();
		render.join(10_000);

		assertSame(task.only(), taken.get());
		assertEquals(Outcome.WAITED, prepared.lastOutcome());
		assertEquals(1, task.runs.get());
		workers.join();
	}

	@Test
	void staleFinishedResultsAreClosedExactlyOnce() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task task = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", task, workers);
		workers.runAll();

		prepared.expire();
		assertEquals(1, task.only().closes.get());
		assertEquals(0, prepared.scale());
		assertEquals(0, prepared.size());
		assertNull(prepared.take("label_2"), "stale results are never handed out");
		prepared.expire();
		prepared.shutdown();
		assertEquals(1, task.only().closes.get(), "never closed twice");
	}

	@Test
	void aStaleQueuedJobNeverRuns() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task task = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", task, workers);

		prepared.expire();
		workers.runAll();
		assertEquals(0, task.runs.get());
		assertEquals(0, prepared.closing());
	}

	@Test
	void aStaleRunningJobClosesWhatItMakesWhenItEnds() throws InterruptedException {
		Preparations<String, Made> prepared = new Preparations<>();
		Workers workers = new Workers();
		Task task = new Task(true);
		prepared.switchTo(2);
		prepared.start("label_2", task, workers);
		task.started.await();

		prepared.expire();
		assertEquals(1, prepared.closing(), "waiting for the job to end");
		assertNull(prepared.take("label_2"));
		task.gate.countDown();
		workers.join();

		assertEquals(1, task.only().closes.get());
		assertEquals(0, prepared.closing());
	}

	@Test
	void anotherScaleMakesTheOldResultsStale() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task atTwo = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", atTwo, workers);
		workers.runAll();

		assertFalse(prepared.switchTo(2), "already prepared for this scale");
		assertEquals(1, prepared.size());
		assertEquals(0, atTwo.only().closes.get());

		assertTrue(prepared.switchTo(3));
		assertEquals(3, prepared.scale());
		assertEquals(0, prepared.size());
		assertEquals(1, atTwo.only().closes.get());

		Task atThree = new Task(false);
		prepared.start("label_3", atThree, workers);
		workers.runAll();
		assertSame(atThree.only(), prepared.take("label_3"));
	}

	@Test
	void aKeyIsPreparedOnce() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		Task first = new Task(false);
		Task second = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", first, workers);
		prepared.start("label_2", second, workers);
		assertEquals(1, workers.queued.size());
		workers.runAll();
		assertSame(first.only(), prepared.take("label_2"));
		assertEquals(0, second.runs.get());
	}

	@Test
	void aFailedJobGivesNothingSoTheCallerLoadsItTheUsualWay() {
		Preparations<String, Made> prepared = new Preparations<>();
		Queue workers = new Queue();
		prepared.switchTo(2);
		prepared.start("on a worker", () -> {
			throw new IOException("no such font");
		}, workers);
		prepared.start("right here", () -> {
			throw new IllegalStateException("FreeType error");
		}, workers);
		workers.queued.removeLast();
		workers.runAll();

		assertNull(prepared.take("on a worker"));
		assertEquals(Outcome.FAILED, prepared.lastOutcome());
		assertNull(prepared.take("right here"));
		assertEquals(Outcome.FAILED, prepared.lastOutcome());
	}

	@Test
	void aJobThePoolRefusesIsMadeWhenAskedFor() {
		Preparations<String, Made> prepared = new Preparations<>();
		Task task = new Task(false);
		prepared.switchTo(2);
		prepared.start("label_2", task, job -> {
			throw new RejectedExecutionException("shut down");
		});
		assertEquals(0, task.runs.get());
		Made made = prepared.take("label_2");
		assertSame(task.only(), made);
		assertEquals(Outcome.MADE_HERE, prepared.lastOutcome());
	}

	@Test
	void shutdownWaitsForRunningJobsAndClosesWhatTheyMake() throws InterruptedException {
		Preparations<String, Made> prepared = new Preparations<>();
		Workers workers = new Workers();
		Queue queue = new Queue();
		Task running = new Task(true);
		Task queued = new Task(false);
		Task taken = new Task(false);
		prepared.switchTo(2);
		prepared.start("running", running, workers);
		prepared.start("queued", queued, queue);
		prepared.start("taken", taken, queue);
		running.started.await();
		prepared.take("taken");

		Thread render = new Thread(prepared::shutdown, "test render thread");
		render.start();
		awaitParked(render);
		assertTrue(render.isAlive(), "shutdown waits for the running job");
		running.gate.countDown();
		render.join(10_000);
		assertFalse(render.isAlive());

		assertEquals(1, running.only().closes.get(), "closed before shutdown returned");
		queue.runAll();
		assertEquals(0, queued.runs.get(), "never started");
		assertEquals(0, taken.only().closes.get(), "the taker's to close");
		workers.join();
	}
}

package com.flintclient.client.graphics;

import com.flintclient.client.FlintClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Things made ahead of use on worker threads (Flint's font providers, see render/FontPreload), for one GUI
 * scale at a time, until the render thread takes them or they go stale.
 *
 * <p>Each job runs at most once, on whichever thread claims it first: a worker, or the render thread when
 * it asks before any worker has started it, so the render thread never waits on a job still queued (only on
 * one a worker is already running; whether that wait beats making it right there is unmeasured).
 * The render thread only ever receives a result whose job has finished. Every result has exactly one owner
 * that closes it: whoever took it, or this class once it went stale (at once if finished, otherwise as soon
 * as its job ends). A job that hadn't started when it went stale never runs.
 *
 * <p>State per key: none → pending (queued or running) → ready → taken, or → stale (closed). The bookkeeping
 * is render thread only; jobs run anywhere.
 */
public final class Preparations<K, V extends AutoCloseable> {
	/** How the last {@link #take} went (for the developer timing log). */
	public enum Outcome {
		/** Nothing was prepared under that key. */
		NONE,
		/** A worker had finished it before it was asked for. */
		READY,
		/** A worker was making it; the caller waited for the rest. */
		WAITED,
		/** No worker had started it, so the caller made it. */
		MADE_HERE,
		/** Its job failed; the caller gets nothing. */
		FAILED
	}

	/** At shutdown, how long to wait for jobs still running (they take milliseconds). */
	private static final long SHUTDOWN_WAIT_SECONDS = 5;

	private final Map<K, Job<V>> jobs = new HashMap<>();
	/** Stale jobs still running: each closes its result when it ends. */
	private final List<CompletableFuture<?>> closing = new ArrayList<>();
	private int scale;
	private Outcome lastOutcome = Outcome.NONE;

	/** The GUI scale being prepared for; 0 for none. */
	public int scale() {
		return scale;
	}

	/** Keys prepared and not taken yet. */
	public int size() {
		return jobs.size();
	}

	/** Stale jobs whose close hasn't happened yet (they're still running). */
	int closing() {
		closing.removeIf(CompletableFuture::isDone);
		return closing.size();
	}

	public Outcome lastOutcome() {
		return lastOutcome;
	}

	/**
	 * Moves to {@code scale}: everything prepared for another scale goes stale. True when the caller should
	 * {@link #start} jobs for it; false when it's the scale already prepared for.
	 */
	public boolean switchTo(int scale) {
		if (scale == this.scale) {
			return false;
		}
		expire();
		this.scale = scale;
		return true;
	}

	/** Queues {@code task} on {@code executor} for {@code key}, unless that key is already prepared. */
	public void start(K key, Callable<V> task, Executor executor) {
		if (jobs.containsKey(key)) {
			return;
		}
		Job<V> job = new Job<>(task);
		jobs.put(key, job);
		try {
			executor.execute(job);
		} catch (RejectedExecutionException e) {
			// Left unclaimed: take() makes it on the caller's thread.
		}
	}

	/**
	 * The result for {@code key}, which the caller now owns: finished already, waited for if a worker is on
	 * it, or made right here if none has started it. Null when nothing was prepared or its job failed.
	 */
	public @Nullable V take(K key) {
		Job<V> job = jobs.remove(key);
		if (job == null) {
			lastOutcome = Outcome.NONE;
			return null;
		}
		Outcome outcome;
		if (job.result.isDone()) {
			outcome = Outcome.READY;
		} else if (job.claim()) {
			job.make();
			outcome = Outcome.MADE_HERE;
		} else {
			outcome = Outcome.WAITED;
		}
		try {
			V value = job.result.join();
			lastOutcome = outcome;
			return value;
		} catch (CompletionException | CancellationException e) {
			FlintClient.LOGGER.debug("Preparing {} off the render thread failed; it's made the usual way", key, e);
			lastOutcome = Outcome.FAILED;
			return null;
		}
	}

	/**
	 * Everything not taken goes stale (a reload replaced the resources it read, or the scale changed):
	 * finished results are closed now, running ones when they end, and queued ones never run.
	 */
	public void expire() {
		for (Job<V> job : jobs.values()) {
			if (!job.cancel()) {
				closing.add(job.result.whenComplete((value, error) -> close(value)));
			}
		}
		jobs.clear();
		closing.removeIf(CompletableFuture::isDone);
		scale = 0;
	}

	/**
	 * Expires everything and waits for stale jobs still running to end and close what they made, so nothing
	 * touches FreeType after Minecraft shuts it down (after FontManager.close).
	 */
	public void shutdown() {
		expire();
		// One wait for all of them, not one per job: a stuck worker can't hold the game's exit up for long.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_WAIT_SECONDS);
		for (CompletableFuture<?> pending : closing) {
			try {
				pending.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			} catch (ExecutionException | CancellationException e) {
				// A failed job made nothing to close.
			} catch (TimeoutException e) {
				FlintClient.LOGGER.warn("Flint font preparations didn't finish within {} s of shutdown", SHUTDOWN_WAIT_SECONDS);
				break;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		closing.clear();
	}

	private static void close(@Nullable AutoCloseable value) {
		if (value == null) {
			return;
		}
		try {
			value.close();
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Couldn't close a stale Flint preparation", e);
		}
	}

	/** One thing to make, by whichever thread claims it first. */
	private static final class Job<V> implements Runnable {
		private final Callable<V> task;
		private final AtomicBoolean claimed = new AtomicBoolean();
		final CompletableFuture<V> result = new CompletableFuture<>();

		Job(Callable<V> task) {
			this.task = task;
		}

		/** On the executor: makes it unless another thread already claimed it. */
		@Override
		public void run() {
			if (claim()) {
				make();
			}
		}

		boolean claim() {
			return claimed.compareAndSet(false, true);
		}

		/** Only by the thread whose {@link #claim()} succeeded. */
		void make() {
			try {
				result.complete(task.call());
			} catch (Throwable e) {
				// Anything at all, so a thread waiting for this job is always released.
				result.completeExceptionally(e);
			}
		}

		/** Stops a job that hasn't started from ever running; false when it already has. */
		boolean cancel() {
			if (claim()) {
				result.cancel(false);
				return true;
			}
			return false;
		}
	}
}

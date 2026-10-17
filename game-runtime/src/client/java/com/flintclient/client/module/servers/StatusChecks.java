package com.flintclient.client.module.servers;

import com.flintclient.client.module.servers.ServerShortcuts.Status;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The shortcut slots' status checks, by server address, and the pings behind them. The ping itself is
 * passed in, so the timing rules can be tested with fake pings.
 *
 * <p>A check is queued, started when a pool thread picks it up, then answered. Only a started check
 * times out ({@link #TIMEOUT_MILLIS} after it started): a ping still waiting for a thread hasn't been
 * tried, so its server isn't offline. An address that doesn't resolve is offline at once.
 *
 * <p>The checks are only touched on the main thread; pool and network threads report through
 * {@code mainThread}, and reports from a stopped {@link PingRound} are dropped.
 */
final class StatusChecks {
	static final long RECHECK_MILLIS = 60_000;
	static final long FRESH_MILLIS = 5 * 60_000;
	static final long TIMEOUT_MILLIS = 15_000;

	/** One status ping, run on a pool thread. */
	interface Ping {
		/**
		 * Resolves the address and opens the status connection, blocking meanwhile; the answer comes later
		 * through {@code onReply}, from any thread. Returns false when it gave up without a connection (the
		 * address didn't resolve); throws when the connection failed.
		 */
		boolean start(Consumer<Result> onReply) throws Exception;
	}

	record Result(Status status, long pingMillis, @Nullable String version) {
		static final Result OFFLINE = new Result(Status.OFFLINE, -1, null);
	}

	private record Check(Status status, boolean started, long at, long pingMillis, @Nullable String version) {
	}

	private final Map<String, Check> checks = new HashMap<>();
	private final Executor pool;
	private final Executor mainThread;
	private final LongSupplier clock;

	/** {@code clock} is in milliseconds and read from any thread. */
	StatusChecks(Executor pool, Executor mainThread, LongSupplier clock) {
		this.pool = pool;
		this.mainThread = mainThread;
		this.clock = clock;
	}

	/** Whether {@code address} needs a ping: it isn't being checked and wasn't checked in the last minute. */
	boolean due(String address) {
		Check last = checks.get(address);
		return last == null || (last.status() != Status.CHECKING && clock.getAsLong() - last.at() >= RECHECK_MILLIS);
	}

	/** Queues a ping of {@code address} in {@code round}; the address shows as checking until it is answered. */
	void ping(String address, PingRound round, Ping ping) {
		checks.put(address, new Check(Status.CHECKING, false, clock.getAsLong(), -1, null));
		pool.execute(round.task(() -> {
			// The timeout counts from here: until now the ping was only waiting for a thread.
			long startedAt = clock.getAsLong();
			mainThread.execute(() -> started(round, address, startedAt));
			try {
				if (!ping.start(result -> report(round, address, result))) {
					report(round, address, Result.OFFLINE);
				}
			} catch (Exception e) {
				report(round, address, Result.OFFLINE);
			}
		}));
	}

	private void started(PingRound round, String address, long at) {
		Check check = checks.get(address);
		if (round.open() && check != null && check.status() == Status.CHECKING && !check.started()) {
			checks.put(address, new Check(Status.CHECKING, true, at, -1, null));
		}
	}

	private void report(PingRound round, String address, Result result) {
		mainThread.execute(() -> {
			if (round.open()) {
				checks.put(address, new Check(result.status(), true, clock.getAsLong(), result.pingMillis(), result.version()));
			}
		});
	}

	/** Marks started checks still unanswered after {@link #TIMEOUT_MILLIS} offline. Called every tick on the menu. */
	void expire() {
		long now = clock.getAsLong();
		for (Map.Entry<String, Check> entry : checks.entrySet()) {
			Check check = entry.getValue();
			if (check.status() == Status.CHECKING && check.started() && now - check.at() > TIMEOUT_MILLIS) {
				entry.setValue(new Check(Status.OFFLINE, true, now, -1, null));
			}
		}
	}

	/** Forgets the checks still unanswered, once their round has stopped; answers already received are kept. */
	void dropUnanswered() {
		checks.values().removeIf(check -> check.status() == Status.CHECKING);
	}

	/** "Online" (or incompatible, or offline) only while the answer is a few minutes old; unknown after. */
	Status status(String address) {
		Check check = checks.get(address);
		if (check == null) {
			return Status.UNKNOWN;
		}
		if (check.status() == Status.CHECKING) {
			return Status.CHECKING;
		}
		return clock.getAsLong() - check.at() <= FRESH_MILLIS ? check.status() : Status.UNKNOWN;
	}

	long pingMillis(String address) {
		Check check = checks.get(address);
		return check == null ? -1 : check.pingMillis();
	}

	@Nullable String version(String address) {
		Check check = checks.get(address);
		return check == null ? null : check.version();
	}
}

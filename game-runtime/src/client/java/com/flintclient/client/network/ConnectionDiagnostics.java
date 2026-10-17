package com.flintclient.client.network;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The last few finished attempts, newest first: outcome, furthest phase, durations, vanilla reason key and
 * when each ended. In memory only, so it's gone when the game exits. Any thread.
 */
public final class ConnectionDiagnostics {
	/** How many finished attempts are kept. */
	public static final int CAPACITY = 8;

	private final int capacity;
	private final AtomicReference<List<ConnectionAttempt>> attempts = new AtomicReference<>(List.of());

	public ConnectionDiagnostics() {
		this(CAPACITY);
	}

	ConnectionDiagnostics(int capacity) {
		this.capacity = capacity;
	}

	/**
	 * Adds one finished attempt, dropping the oldest beyond the capacity. Once per attempt, so copying is
	 * cheap enough. Placed by attempt number: the threads ending two attempts can add them in either order.
	 */
	void add(ConnectionAttempt finished) {
		attempts.updateAndGet(old -> {
			List<ConnectionAttempt> next = new ArrayList<>(old.size() + 1);
			int at = 0;
			while (at < old.size() && old.get(at).id() > finished.id()) {
				at++;
			}
			next.addAll(old);
			next.add(at, finished);
			return List.copyOf(next.subList(0, Math.min(next.size(), capacity)));
		});
	}

	/** Newest first; an unchangeable copy. */
	public List<ConnectionAttempt> history() {
		return attempts.get();
	}

	void clear() {
		attempts.set(List.of());
	}
}

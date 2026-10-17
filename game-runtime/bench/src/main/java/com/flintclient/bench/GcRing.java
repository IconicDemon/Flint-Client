package com.flintclient.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * Garbage-collection events in a preallocated ring. GC notifications arrive on a JMX thread while the
 * result is written on the client thread, so every access holds this object's lock; events are rare
 * (at most a few per second), so the lock costs nothing measurable. When full, the oldest are dropped.
 */
final class GcRing {
	record Event(long tNs, long uptimeMs, String name, String action, String cause, long durationMs) {
	}

	private final long[] tNs;
	private final long[] uptimeMs;
	private final long[] durationMs;
	private final String[] name;
	private final String[] action;
	private final String[] cause;
	private long added;

	GcRing(int capacity) {
		tNs = new long[capacity];
		uptimeMs = new long[capacity];
		durationMs = new long[capacity];
		name = new String[capacity];
		action = new String[capacity];
		cause = new String[capacity];
	}

	synchronized void add(long eventNs, long startUptimeMs, String gcName, String gcAction, String gcCause, long gcDurationMs) {
		int i = (int) (added % tNs.length);
		tNs[i] = eventNs;
		uptimeMs[i] = startUptimeMs;
		durationMs[i] = gcDurationMs;
		name[i] = gcName;
		action[i] = gcAction;
		cause[i] = gcCause;
		added++;
	}

	/** The retained events, oldest first. */
	synchronized List<Event> snapshot() {
		int capacity = tNs.length;
		int size = (int) Math.min(added, capacity);
		long first = added - size;
		List<Event> events = new ArrayList<>(size);
		for (long n = first; n < added; n++) {
			int i = (int) (n % capacity);
			events.add(new Event(tNs[i], uptimeMs[i], name[i], action[i], cause[i], durationMs[i]));
		}
		return events;
	}

	synchronized long dropped() {
		return Math.max(0, added - tNs.length);
	}
}

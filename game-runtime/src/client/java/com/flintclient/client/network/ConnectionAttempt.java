package com.flintclient.client.network;

import org.jspecify.annotations.Nullable;

/**
 * One multiplayer connection attempt as a value: its phase, when it reached each step ({@link System#nanoTime()}
 * timestamps), and once it's over, how it ended. Each change makes a new copy, and a copy is never changed
 * once {@link ConnectionTracker} has published it, so any thread can read one. Holds no host, address or
 * server text; the tracked connection itself is held only while the attempt runs and isn't exposed.
 */
public final class ConnectionAttempt implements Cloneable {
	/** A duration that wasn't measured. */
	public static final long UNMEASURED = -1L;
	/** A step not reached. */
	static final long UNSET = Long.MIN_VALUE;
	/** Before the first attempt of the session. */
	static final ConnectionAttempt IDLE = new ConnectionAttempt();

	private long id;
	private ConnectionPhase phase = ConnectionPhase.IDLE;
	private ConnectionPhase reached = ConnectionPhase.IDLE;
	private @Nullable Object connection;
	private long connectorThread = -1L;
	private boolean kicked;
	private int reconfigurations;
	private @Nullable String reasonKey;
	private @Nullable ConnectionOutcome outcome;
	private long phaseAt = UNSET;
	private long startedAt = UNSET;
	private long resolvingAt = UNSET;
	private long openingAt = UNSET;
	private long openedAt = UNSET;
	private long authenticatingAt = UNSET;
	private long authenticatedAt = UNSET;
	private long connectedAt = UNSET;
	private long endedAt = UNSET;
	private long endedAtMillis;

	private ConnectionAttempt() {
	}

	/** A new attempt, numbered after {@code previous}, whose lookup and socket run on {@code connectorThread}. */
	static ConnectionAttempt start(ConnectionAttempt previous, long connectorThread, long now) {
		ConnectionAttempt next = new ConnectionAttempt();
		next.id = previous.id + 1;
		next.connectorThread = connectorThread;
		next.startedAt = now;
		return next.enter(ConnectionPhase.CONNECTING, now);
	}

	private ConnectionAttempt copy() {
		try {
			return (ConnectionAttempt) clone();
		} catch (CloneNotSupportedException e) {
			throw new AssertionError(e);
		}
	}

	/** Moved on to {@code next}; the first time a step is entered is when it counts from. */
	ConnectionAttempt enter(ConnectionPhase next, long now) {
		ConnectionAttempt copy = copy();
		copy.phase = next;
		copy.phaseAt = now;
		if (next.progress()) {
			copy.reached = next;
		}
		switch (next) {
			case RESOLVING -> copy.resolvingAt = first(resolvingAt, now);
			case OPENING -> copy.openingAt = first(openingAt, now);
			case AUTHENTICATING -> copy.authenticatingAt = first(authenticatingAt, now);
			case CONNECTED -> copy.connectedAt = first(connectedAt, now);
			default -> {
			}
		}
		return copy;
	}

	/** The server is reconfiguring the session: back to joining, still the same attempt. */
	ConnectionAttempt reconfigure(long now) {
		ConnectionAttempt copy = enter(ConnectionPhase.JOINING, now);
		copy.reconfigurations++;
		return copy;
	}

	ConnectionAttempt bind(Object connection) {
		ConnectionAttempt copy = copy();
		copy.connection = connection;
		return copy;
	}

	ConnectionAttempt opened(long now) {
		ConnectionAttempt copy = copy();
		copy.openedAt = now;
		return copy;
	}

	ConnectionAttempt authenticated(long now) {
		ConnectionAttempt copy = copy();
		copy.authenticatedAt = now;
		return copy;
	}

	/** Keeps the first reason given: vanilla shows the one from the first disconnect while connected. */
	ConnectionAttempt reason(@Nullable String key, boolean fromServer) {
		ConnectionAttempt copy = copy();
		copy.kicked |= fromServer;
		if (copy.reasonKey == null) {
			copy.reasonKey = key;
		}
		return copy;
	}

	/** Over: the connection reference is dropped so nothing of the session stays reachable from here. */
	ConnectionAttempt end(ConnectionOutcome result, @Nullable String key, long now, long wallMillis) {
		ConnectionAttempt copy = enter(ConnectionPhase.DISCONNECTED, now);
		copy.outcome = result;
		if (key != null) {
			copy.reasonKey = key;
		}
		copy.connection = null;
		copy.endedAt = now;
		copy.endedAtMillis = wallMillis;
		return copy;
	}

	private static long first(long existing, long now) {
		return existing == UNSET ? now : existing;
	}

	private static long between(long from, long to) {
		return from == UNSET || to == UNSET ? UNMEASURED : to - from;
	}

	/** 1 for the session's first attempt; 0 before any. */
	public long id() {
		return id;
	}

	public ConnectionPhase phase() {
		return phase;
	}

	/** The step it was in before it began closing (never disconnecting or disconnected). */
	public ConnectionPhase reached() {
		return reached;
	}

	/** Started and not over yet. */
	public boolean running() {
		return phase != ConnectionPhase.IDLE && phase != ConnectionPhase.DISCONNECTED;
	}

	/** How it ended; {@link ConnectionOutcome#JOINED} while it's connected, null while it's getting there. */
	public @Nullable ConnectionOutcome outcome() {
		if (outcome != null) {
			return outcome;
		}
		return running() && connectedAt != UNSET ? ConnectionOutcome.JOINED : null;
	}

	/** The vanilla translation key of the disconnect reason, if there was one. */
	public @Nullable String reasonKey() {
		return reasonKey;
	}

	/** How many times the server has reconfigured the session since it first reached play. */
	public int reconfigurations() {
		return reconfigurations;
	}

	/** Wall-clock time it ended ({@link System#currentTimeMillis()}), or 0 while it runs. */
	public long endedAtMillis() {
		return endedAtMillis;
	}

	/** The address lookup. */
	public long resolveNanos() {
		return between(resolvingAt, openingAt);
	}

	/** The socket connect. */
	public long connectNanos() {
		return between(openingAt, openedAt);
	}

	/** Session authentication (online mode only), part of {@link #joinNanos()}. */
	public long authenticateNanos() {
		return between(authenticatingAt, authenticatedAt);
	}

	/** Login and configuration: from the socket opening to play. */
	public long joinNanos() {
		return between(openedAt, connectedAt);
	}

	/** From the attempt starting to play. */
	public long totalNanos() {
		return between(startedAt, connectedAt);
	}

	/** Time spent connected (to the end, or to {@code now} while connected). */
	public long connectedNanos(long now) {
		return between(connectedAt, endedAt != UNSET ? endedAt : now);
	}

	/** From the start to the end (or to {@code now} while it runs). */
	public long elapsedNanos(long now) {
		return between(startedAt, endedAt != UNSET ? endedAt : now);
	}

	@Nullable Object connection() {
		return connection;
	}

	long connectorThread() {
		return connectorThread;
	}

	boolean socketOpen() {
		return openedAt != UNSET;
	}

	boolean kicked() {
		return kicked;
	}

	boolean everConnected() {
		return connectedAt != UNSET;
	}

	boolean authenticating() {
		return authenticatingAt != UNSET && authenticatedAt == UNSET;
	}

	long phaseAt() {
		return phaseAt;
	}
}

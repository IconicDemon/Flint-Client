package com.flintclient.client.network;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Follows one multiplayer connection attempt at a time through its phases. Events arrive from the main
 * thread, the attempt's connector thread, Netty's I/O threads and Minecraft's I/O pool, so the state is one
 * {@link AtomicReference} to an immutable {@link ConnectionAttempt}, changed by compare-and-set: no locks,
 * and no event can be half-applied or lost to a race. Exactly one thread ends an attempt, and that thread
 * alone logs it and adds it to the {@link ConnectionDiagnostics}.
 *
 * <p>Only the attempt the connect screen started is followed: lookup and socket events count only from
 * its connector thread (so server-list pings, Realms and older attempts' threads are ignored), and every
 * later event only for the connection that thread opened, compared by identity (so singleplayer's
 * in-memory connection and stale connections are ignored). Events that don't fit the attempt's phase
 * change nothing. An event for anything untracked costs one volatile read and allocates nothing.
 */
public final class ConnectionTracker {
	private final AtomicReference<ConnectionAttempt> current = new AtomicReference<>(ConnectionAttempt.IDLE);
	private final LongSupplier nanoClock;
	private final LongSupplier wallClock;
	private final ConnectionDiagnostics diagnostics;
	private final Consumer<String> log;
	private volatile boolean logTransitions;

	/** {@code log} gets one line per finished attempt (and per phase change once {@link #logTransitions} is on). */
	public ConnectionTracker(LongSupplier nanoClock, LongSupplier wallClock, ConnectionDiagnostics diagnostics, Consumer<String> log) {
		this.nanoClock = nanoClock;
		this.wallClock = wallClock;
		this.diagnostics = diagnostics;
		this.log = log;
	}

	/** The latest attempt: running, finished, or {@link ConnectionPhase#IDLE} before the first. */
	public ConnectionAttempt current() {
		return current.get();
	}

	public ConnectionDiagnostics diagnostics() {
		return diagnostics;
	}

	/** Developer log ({@code dev.network}): also one line per phase change. */
	public void logTransitions(boolean on) {
		logTransitions = on;
	}

	/** Whether {@code connection} is the running attempt's connection (identity). Any thread; allocates nothing. */
	public boolean tracks(@Nullable Object connection) {
		return connection != null && current.get().connection() == connection;
	}

	long now() {
		return nanoClock.getAsLong();
	}

	/** Main thread, as the connect screen starts its connector thread. An attempt still running is ended first. */
	public void attemptStarted(long connectorThread) {
		update((attempt, now) -> attempt.running() ? end(attempt, superseded(attempt), null, now) : attempt);
		update((attempt, now) -> ConnectionAttempt.start(attempt, connectorThread, now));
	}

	/** The address lookup starts on {@code thread}. */
	public void resolving(long thread) {
		if (current.get().connectorThread() != thread) {
			return;
		}
		update((attempt, now) -> onConnector(attempt, thread) && attempt.phase() == ConnectionPhase.CONNECTING
			? attempt.enter(ConnectionPhase.RESOLVING, now) : attempt);
	}

	/** The lookup on {@code thread} returned; nothing {@code found} means vanilla shows "unknown host" next. */
	public void resolved(long thread, boolean found) {
		if (current.get().connectorThread() != thread) {
			return;
		}
		update((attempt, now) -> {
			if (!onConnector(attempt, thread) || attempt.phase() != ConnectionPhase.RESOLVING) {
				return attempt;
			}
			return found ? attempt.enter(ConnectionPhase.OPENING, now) : end(attempt, ConnectionOutcome.UNKNOWN_HOST, DisconnectReasons.UNKNOWN_HOST, now);
		});
	}

	/**
	 * {@code thread} started opening {@code connection}'s socket. Returns whether that made it the tracked
	 * connection (so its socket result is worth listening for).
	 */
	public boolean socketOpening(long thread, Object connection) {
		if (current.get().connectorThread() != thread) {
			return false;
		}
		ConnectionAttempt after = update((attempt, now) -> {
			if (!onConnector(attempt, thread) || attempt.connection() != null) {
				return attempt;
			}
			if (attempt.phase() == ConnectionPhase.OPENING) {
				return attempt.bind(connection);
			}
			// A lookup hook that didn't fire (another mod's resolver): still the attempt's socket.
			return attempt.phase() == ConnectionPhase.CONNECTING || attempt.phase() == ConnectionPhase.RESOLVING
				? attempt.enter(ConnectionPhase.OPENING, now).bind(connection) : attempt;
		});
		return after.connection() == connection;
	}

	/** Netty: the tracked socket is open. */
	public void socketOpened(Object connection) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> attempt.running() && attempt.connection() == connection && !attempt.socketOpen() ? attempt.opened(now) : attempt);
	}

	/** Netty: the tracked socket couldn't be opened ({@code outcome} from {@link DisconnectReasons#outcome(Throwable)}). */
	public void socketFailed(Object connection, ConnectionOutcome outcome) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> attempt.running() && attempt.connection() == connection && !attempt.socketOpen()
			? end(attempt, outcome, null, now) : attempt);
	}

	/** Netty: the server asked for session authentication. */
	public void authenticating(Object connection) {
		if (tracks(connection)) {
			advance(connection, ConnectionPhase.AUTHENTICATING, ConnectionPhase.OPENING);
		}
	}

	/** Netty or the I/O pool: authentication is done and encryption starts. */
	public void authenticated(Object connection) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> attempt.running() && attempt.connection() == connection && attempt.authenticating()
			? attempt.authenticated(now) : attempt);
	}

	/** Netty: login finished; configuration follows. */
	public void loginFinished(Object connection) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> {
			if (!attempt.running() || attempt.connection() != connection
				|| (attempt.phase() != ConnectionPhase.OPENING && attempt.phase() != ConnectionPhase.AUTHENTICATING)) {
				return attempt;
			}
			ConnectionAttempt joining = attempt.authenticating() ? attempt.authenticated(now) : attempt;
			return joining.enter(ConnectionPhase.JOINING, now);
		});
	}

	/** Main thread: the server is reconfiguring a session in play (a proxy switching servers, for example). */
	public void reconfiguring(Object connection) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> attempt.running() && attempt.connection() == connection && attempt.phase() == ConnectionPhase.CONNECTED
			? attempt.reconfigure(now) : attempt);
	}

	/** Main thread: play started. */
	public void connected(Object connection) {
		if (tracks(connection)) {
			advance(connection, ConnectionPhase.CONNECTED, ConnectionPhase.OPENING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.JOINING);
		}
	}

	/** Netty: the server sent a disconnect; {@code key} is its reason's translation key, if any. */
	public void kicked(Object connection, @Nullable String key) {
		if (!tracks(connection)) {
			return;
		}
		String vanilla = DisconnectReasons.vanillaKey(key);
		update((attempt, now) -> attempt.running() && attempt.connection() == connection ? attempt.reason(vanilla, true) : attempt);
	}

	/**
	 * Any thread: the tracked connection is being closed with this reason. The connect screen's Cancel ends
	 * the attempt here, since nothing reports that disconnect later. The reconfiguration screen's Disconnect
	 * sends the same reason; after play that's the player leaving. After a kick it's the server's packet
	 * borrowing the key (or the player cancelling a session the server already ended): still a kick.
	 */
	public void disconnecting(Object connection, @Nullable String key) {
		if (!tracks(connection)) {
			return;
		}
		String vanilla = DisconnectReasons.vanillaKey(key);
		update((attempt, now) -> {
			if (!attempt.running() || attempt.connection() != connection) {
				return attempt;
			}
			if (DisconnectReasons.ABORTED.equals(vanilla)) {
				if (attempt.kicked()) {
					return end(attempt, ConnectionOutcome.KICKED, null, now);
				}
				return end(attempt, attempt.everConnected() ? ConnectionOutcome.QUIT : ConnectionOutcome.CANCELLED, vanilla, now);
			}
			ConnectionAttempt noted = attempt.reason(vanilla, false);
			return noted.phase() == ConnectionPhase.DISCONNECTING ? noted : noted.enter(ConnectionPhase.DISCONNECTING, now);
		});
	}

	/** Main thread: Minecraft handled the tracked connection's disconnect with this reason. */
	public void disconnected(Object connection, @Nullable String key) {
		if (!tracks(connection)) {
			return;
		}
		String vanilla = DisconnectReasons.vanillaKey(key);
		update((attempt, now) -> {
			if (!attempt.running() || attempt.connection() != connection) {
				return attempt;
			}
			// The generic reason only means no reason was stored; one seen earlier says more.
			String reason = vanilla == null || (DisconnectReasons.GENERIC.equals(vanilla) && attempt.reasonKey() != null) ? attempt.reasonKey() : vanilla;
			return end(attempt, DisconnectReasons.outcome(reason, attempt.kicked()), reason, now);
		});
	}

	/** Main thread: the session in play was torn down by the client (quitting, or leaving for another server). */
	public void closed(Object connection) {
		if (!tracks(connection)) {
			return;
		}
		update((attempt, now) -> {
			if (!attempt.running() || attempt.connection() != connection) {
				return attempt;
			}
			boolean known = attempt.reasonKey() != null || attempt.kicked();
			return end(attempt, known ? DisconnectReasons.outcome(attempt.reasonKey(), attempt.kicked()) : ConnectionOutcome.QUIT, null, now);
		});
	}

	/**
	 * Main thread: a disconnected screen appeared while the attempt had no login under way (no handler of
	 * its own reports those). {@code cause} is the socket failure if one is known by then.
	 */
	public void failedWithoutConnection(@Nullable ConnectionOutcome cause, @Nullable String key) {
		ConnectionAttempt latest = current.get();
		if (!latest.running() || !beforeLogin(latest.phase())) {
			return;
		}
		String vanilla = DisconnectReasons.vanillaKey(key);
		update((attempt, now) -> attempt.running() && beforeLogin(attempt.phase())
			? end(attempt, cause != null ? cause : DisconnectReasons.outcome(vanilla, false), vanilla, now) : attempt);
	}

	/**
	 * Main thread: the connect screen went away. Before the socket exists, only its Cancel button does that.
	 * An attempt already closing before play ends here with its reason: if Cancel came just after the server
	 * closed it, nothing ticks the connection any more, so vanilla never reports that disconnect.
	 */
	public void connectScreenClosed() {
		ConnectionAttempt latest = current.get();
		if (!latest.running()) {
			return;
		}
		update((attempt, now) -> {
			if (!attempt.running()) {
				return attempt;
			}
			if (beforeSocket(attempt)) {
				return end(attempt, ConnectionOutcome.CANCELLED, null, now);
			}
			return attempt.phase() == ConnectionPhase.DISCONNECTING && !attempt.everConnected()
				? end(attempt, DisconnectReasons.outcome(attempt.reasonKey(), attempt.kicked()), null, now) : attempt;
		});
	}

	/** Ends a running attempt quietly (no log line, no history entry): the Network Core is stopping after a failure. */
	public void reset() {
		while (true) {
			ConnectionAttempt attempt = current.get();
			if (!attempt.running()
				|| current.compareAndSet(attempt, attempt.end(ConnectionOutcome.UNKNOWN, null, nanoClock.getAsLong(), wallClock.getAsLong()))) {
				return;
			}
		}
	}

	private void advance(Object connection, ConnectionPhase to, ConnectionPhase... from) {
		update((attempt, now) -> {
			if (!attempt.running() || attempt.connection() != connection) {
				return attempt;
			}
			for (ConnectionPhase phase : from) {
				if (attempt.phase() == phase) {
					return attempt.enter(to, now);
				}
			}
			return attempt;
		});
	}

	private ConnectionAttempt end(ConnectionAttempt attempt, ConnectionOutcome outcome, @Nullable String key, long now) {
		return attempt.end(outcome, key, now, wallClock.getAsLong());
	}

	private static boolean onConnector(ConnectionAttempt attempt, long thread) {
		return attempt.running() && attempt.connectorThread() == thread;
	}

	private static boolean beforeLogin(ConnectionPhase phase) {
		return phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RESOLVING || phase == ConnectionPhase.OPENING;
	}

	private static boolean beforeSocket(ConnectionAttempt attempt) {
		return attempt.phase() == ConnectionPhase.CONNECTING || attempt.phase() == ConnectionPhase.RESOLVING
			|| (attempt.phase() == ConnectionPhase.OPENING && !attempt.socketOpen());
	}

	// What a still-running attempt ended as when a new one replaced it.
	private static ConnectionOutcome superseded(ConnectionAttempt attempt) {
		return attempt.reasonKey() != null || attempt.kicked() ? DisconnectReasons.outcome(attempt.reasonKey(), attempt.kicked()) : ConnectionOutcome.UNKNOWN;
	}

	@FunctionalInterface
	private interface Step {
		/** The attempt after this event, or {@code attempt} itself when the event changes nothing. */
		ConnectionAttempt apply(ConnectionAttempt attempt, long now);
	}

	/**
	 * Applies {@code step} to the current attempt until the compare-and-set lands. The clock is read after
	 * the attempt, so a published attempt's timestamps never run backwards. Returns the attempt in place after.
	 */
	private ConnectionAttempt update(Step step) {
		while (true) {
			ConnectionAttempt before = current.get();
			ConnectionAttempt after = step.apply(before, nanoClock.getAsLong());
			if (after == before) {
				return before;
			}
			if (current.compareAndSet(before, after)) {
				published(before, after);
				return after;
			}
		}
	}

	// Only the thread whose change landed gets here, once per change.
	private void published(ConnectionAttempt before, ConnectionAttempt after) {
		if (logTransitions && (before.phase() != after.phase() || before.id() != after.id())) {
			log.accept(NetworkLog.transition(before, after));
		}
		if (after.everConnected() && !before.everConnected() && before.id() == after.id()) {
			log.accept(NetworkLog.joined(after));
		}
		if (!after.running() && before.running() && before.id() == after.id()) {
			diagnostics.add(after);
			log.accept(NetworkLog.ended(after));
		}
	}
}

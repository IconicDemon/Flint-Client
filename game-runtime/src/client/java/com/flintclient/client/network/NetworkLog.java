package com.flintclient.client.network;

import java.util.Locale;

/**
 * The Network Core's log lines. They're built only from an attempt's phases, durations, outcome and vanilla
 * reason key, so they can't contain a host, address, port, server name or anything the server said.
 */
final class NetworkLog {
	static final String PREFIX = "Flint Network Core: ";
	private static final long MILLI = 1_000_000L;
	private static final long SECOND = 1_000_000_000L;
	private static final long MINUTE = 60 * SECOND;
	private static final long HOUR = 60 * MINUTE;

	private NetworkLog() {
	}

	/** When an attempt first reaches play: "joined in 1.24 s (resolve 3 ms, connect 2 ms, login+configuration 1.21 s)". */
	static String joined(ConnectionAttempt attempt) {
		StringBuilder parts = new StringBuilder();
		part(parts, "resolve", attempt.resolveNanos());
		part(parts, "connect", attempt.connectNanos());
		part(parts, "login+configuration", attempt.joinNanos());
		if (attempt.authenticateNanos() != ConnectionAttempt.UNMEASURED) {
			parts.append(" including authentication ").append(duration(attempt.authenticateNanos()));
		}
		return PREFIX + "joined in " + duration(attempt.totalNanos()) + (parts.isEmpty() ? "" : " (" + parts + ")");
	}

	/**
	 * When an attempt is over: "disconnected (kicked, multiplayer.disconnect.kicked) after 12 min 3 s connected",
	 * "connection cancelled after 2.10 s while resolving" or "connection failed (refused) after 4 ms while opening".
	 */
	static String ended(ConnectionAttempt attempt) {
		ConnectionOutcome outcome = attempt.outcome();
		String reason = (outcome == null ? ConnectionOutcome.UNKNOWN : outcome).label()
			+ (attempt.reasonKey() == null ? "" : ", " + attempt.reasonKey());
		if (attempt.everConnected()) {
			return PREFIX + "disconnected (" + reason + ") after " + duration(attempt.connectedNanos(0L)) + " connected";
		}
		String where = " after " + duration(attempt.elapsedNanos(0L)) + " while " + step(attempt);
		if (outcome == ConnectionOutcome.CANCELLED) {
			return PREFIX + "connection cancelled" + where;
		}
		return PREFIX + "connection failed (" + reason + ")" + where;
	}

	/** Developer log ({@code dev.network}): one line per phase change. */
	static String transition(ConnectionAttempt before, ConnectionAttempt after) {
		if (before.id() != after.id()) {
			return PREFIX + "attempt " + after.id() + " " + after.phase().word();
		}
		String line = PREFIX + "attempt " + after.id() + " " + before.phase().word() + " -> " + after.phase().word()
			+ " after " + duration(after.phaseAt() - before.phaseAt());
		if (after.reconfigurations() > before.reconfigurations()) {
			line += " (reconfiguration)";
		} else if (after.phase() == ConnectionPhase.DISCONNECTED && after.outcome() != null) {
			line += " (" + after.outcome().label() + ")";
		}
		return line;
	}

	/** Where a failed attempt was: once the socket is open, "opening" becomes "logging in". */
	private static String step(ConnectionAttempt attempt) {
		ConnectionPhase reached = attempt.reached();
		return reached == ConnectionPhase.OPENING && attempt.socketOpen() ? "logging in" : reached.word();
	}

	private static void part(StringBuilder parts, String name, long nanos) {
		if (nanos == ConnectionAttempt.UNMEASURED) {
			return;
		}
		if (!parts.isEmpty()) {
			parts.append(", ");
		}
		parts.append(name).append(' ').append(duration(nanos));
	}

	/** "<1 ms", "3 ms", "1.24 s", "12 min 3 s", "2 h 5 min". */
	static String duration(long nanos) {
		if (nanos < 0) {
			return "?";
		}
		if (nanos < MILLI) {
			return "<1 ms";
		}
		if (nanos < SECOND) {
			return nanos / MILLI + " ms";
		}
		if (nanos < MINUTE) {
			return String.format(Locale.ROOT, "%.2f s", nanos / (double) SECOND);
		}
		if (nanos < HOUR) {
			return nanos / MINUTE + " min " + nanos % MINUTE / SECOND + " s";
		}
		return nanos / HOUR + " h " + nanos % HOUR / MINUTE + " min";
	}
}

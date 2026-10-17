package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NetworkLogTest {
	private static final long MS = 1_000_000L;

	@Test
	void durationsReadNaturally() {
		assertEquals("<1 ms", NetworkLog.duration(0));
		assertEquals("<1 ms", NetworkLog.duration(MS - 1));
		assertEquals("1 ms", NetworkLog.duration(MS));
		assertEquals("999 ms", NetworkLog.duration(999 * MS + MS / 2));
		assertEquals("1.00 s", NetworkLog.duration(1_000 * MS));
		assertEquals("1.24 s", NetworkLog.duration(1_240 * MS));
		assertEquals("59.99 s", NetworkLog.duration(59_990 * MS));
		assertEquals("1 min 0 s", NetworkLog.duration(60_000 * MS));
		assertEquals("12 min 3 s", NetworkLog.duration((12 * 60 + 3) * 1_000 * MS));
		assertEquals("2 h 5 min", NetworkLog.duration((2 * 3600 + 5 * 60 + 59) * 1_000 * MS));
		assertEquals("?", NetworkLog.duration(-1));
	}

	@Test
	void aJoinWithoutALookupLeavesItOut() {
		ConnectionAttempt attempt = ConnectionAttempt.start(ConnectionAttempt.IDLE, 1, 0)
			.enter(ConnectionPhase.OPENING, 0)
			.opened(5 * MS)
			.enter(ConnectionPhase.JOINING, 20 * MS)
			.enter(ConnectionPhase.CONNECTED, 400 * MS);
		assertEquals("Flint Network Core: joined in 400 ms (connect 5 ms, login+configuration 395 ms)", NetworkLog.joined(attempt));
	}

	@Test
	void anEndedAttemptSaysWhereItGotTo() {
		ConnectionAttempt started = ConnectionAttempt.start(ConnectionAttempt.IDLE, 1, 0);
		assertEquals("Flint Network Core: connection cancelled after 2 ms while starting",
			NetworkLog.ended(started.end(ConnectionOutcome.CANCELLED, null, 2 * MS, 0)));
		ConnectionAttempt opening = started.enter(ConnectionPhase.RESOLVING, MS).enter(ConnectionPhase.OPENING, 3 * MS);
		assertEquals("Flint Network Core: connection failed (network error, disconnect.genericReason) after 9 ms while opening",
			NetworkLog.ended(opening.end(ConnectionOutcome.NETWORK_ERROR, "disconnect.genericReason", 9 * MS, 0)));
		ConnectionAttempt joining = opening.opened(4 * MS).enter(ConnectionPhase.JOINING, 8 * MS);
		assertEquals("Flint Network Core: connection failed (kicked) after 30 ms while joining",
			NetworkLog.ended(joining.end(ConnectionOutcome.KICKED, null, 30 * MS, 0)));
	}
}

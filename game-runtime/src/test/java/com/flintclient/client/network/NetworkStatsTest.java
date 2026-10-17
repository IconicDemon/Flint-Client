package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NetworkStatsTest {
	private static final long MS = 1_000_000L;
	private static final long CONNECTOR = 9L;

	/** Answers only for the connection it's told is live, and notes what it was asked about. */
	private static final class FakePlatform implements NetworkPlatform {
		final List<Object> asked = new ArrayList<>();
		Object live;

		@Override
		public int latencyMillis(Object connection) {
			asked.add(connection);
			return connection == live ? 42 : UNKNOWN;
		}

		@Override
		public float packetsReceivedPerSecond(Object connection) {
			asked.add(connection);
			return connection == live ? 37.5f : UNKNOWN;
		}

		@Override
		public float packetsSentPerSecond(Object connection) {
			asked.add(connection);
			return connection == live ? 21.25f : UNKNOWN;
		}
	}

	private final AtomicLong clock = new AtomicLong(0);
	private final ConnectionTracker tracker = new ConnectionTracker(clock::get, () -> 0L, new ConnectionDiagnostics(), line -> {
	});
	private final FakePlatform platform = new FakePlatform();
	private final NetworkStats stats = new NetworkStats(tracker, platform);
	private final Object connection = new Object();

	private void joinAndPlay(long millis) {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, true);
		tracker.socketOpening(CONNECTOR, connection);
		tracker.socketOpened(connection);
		tracker.loginFinished(connection);
		tracker.connected(connection);
		clock.addAndGet(millis * MS);
	}

	@Test
	void nothingIsReadBeforeTheSessionIsInPlay() {
		NetworkSnapshot idle = stats.snapshot();
		assertEquals(ConnectionPhase.IDLE, idle.phase());
		assertEquals(-1, idle.connectedMillis());
		assertFalse(idle.latencyKnown());
		assertEquals(NetworkPlatform.UNKNOWN, idle.packetsReceivedPerSecond());
		tracker.attemptStarted(CONNECTOR);
		assertEquals(NetworkPlatform.UNKNOWN, stats.latencyMillis());
		assertTrue(platform.asked.isEmpty(), "singleplayer and connecting never reach the platform");
	}

	@Test
	void inPlayItReadsTheTrackedConnection() {
		platform.live = connection;
		joinAndPlay(90_000);
		NetworkSnapshot snapshot = stats.snapshot();
		assertEquals(ConnectionPhase.CONNECTED, snapshot.phase());
		assertEquals(90_000, snapshot.connectedMillis());
		assertEquals(42, snapshot.latencyMillis());
		assertTrue(snapshot.latencyKnown());
		assertEquals(37.5f, snapshot.packetsReceivedPerSecond());
		assertEquals(21.25f, snapshot.packetsSentPerSecond());
		assertSame(tracker.current(), snapshot.lastAttempt());
		assertEquals(42, stats.latencyMillis());
		assertEquals(37.5f, stats.packetsReceivedPerSecond());
		assertEquals(21.25f, stats.packetsSentPerSecond());
		assertEquals(90_000, stats.connectedMillis());
		for (Object asked : platform.asked) {
			assertSame(connection, asked);
		}
	}

	@Test
	void afterTheSessionEndsTheReadingsAreUnknownAndTheAttemptIsHistory() {
		platform.live = connection;
		joinAndPlay(1_000);
		tracker.closed(connection);
		assertEquals(ConnectionPhase.DISCONNECTED, stats.phase());
		assertEquals(NetworkPlatform.UNKNOWN, stats.latencyMillis());
		assertEquals(-1, stats.connectedMillis());
		assertEquals(1, stats.history().size());
		assertEquals(ConnectionOutcome.QUIT, stats.snapshot().lastAttempt().outcome());
	}

	@Test
	void reconfiguringPausesTheReadings() {
		platform.live = connection;
		joinAndPlay(1_000);
		tracker.reconfiguring(connection);
		assertEquals(ConnectionPhase.JOINING, stats.phase());
		assertEquals(NetworkPlatform.UNKNOWN, stats.latencyMillis());
		assertEquals(-1, stats.connectedMillis());
	}
}

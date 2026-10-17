package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ConnectionTrackerTest {
	private static final long MS = 1_000_000L;
	private static final long CONNECTOR = 41L;
	private static final long OTHER_THREAD = 77L;
	private static final long WALL = 1_760_000_000_000L;
	private static final Pattern IP = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	private final AtomicLong clock = new AtomicLong(5_000 * MS);
	private final List<String> log = Collections.synchronizedList(new ArrayList<>());
	private final ConnectionTracker tracker = new ConnectionTracker(clock::get, () -> WALL, new ConnectionDiagnostics(), log::add);
	private final Object connection = new Object();

	private void advance(long millis) {
		clock.addAndGet(millis * MS);
	}

	private ConnectionAttempt current() {
		return tracker.current();
	}

	private List<ConnectionAttempt> history() {
		return tracker.diagnostics().history();
	}

	/** Start, a 3 ms lookup and a 2 ms socket connect on {@code socket}. */
	private void openSocket(Object socket) {
		tracker.attemptStarted(CONNECTOR);
		advance(1);
		tracker.resolving(CONNECTOR);
		advance(3);
		tracker.resolved(CONNECTOR, true);
		assertTrue(tracker.socketOpening(CONNECTOR, socket));
		advance(2);
		tracker.socketOpened(socket);
	}

	/** {@link #openSocket} then 1.21 s of login and configuration (offline mode: no authentication). */
	private void join(Object socket) {
		openSocket(socket);
		advance(10);
		tracker.loginFinished(socket);
		advance(1200);
		tracker.connected(socket);
	}

	@Test
	void anAttemptGoesThroughEachPhaseToPlay() {
		assertEquals(ConnectionPhase.IDLE, current().phase());
		tracker.attemptStarted(CONNECTOR);
		assertEquals(ConnectionPhase.CONNECTING, current().phase());
		assertEquals(1, current().id());
		tracker.resolving(CONNECTOR);
		assertEquals(ConnectionPhase.RESOLVING, current().phase());
		tracker.resolved(CONNECTOR, true);
		assertEquals(ConnectionPhase.OPENING, current().phase());
		assertTrue(tracker.socketOpening(CONNECTOR, connection));
		assertTrue(tracker.tracks(connection));
		tracker.socketOpened(connection);
		assertEquals(ConnectionPhase.OPENING, current().phase(), "login has started but the server hasn't answered");
		tracker.loginFinished(connection);
		assertEquals(ConnectionPhase.JOINING, current().phase());
		assertNull(current().outcome(), "still on its way");
		tracker.connected(connection);
		assertEquals(ConnectionPhase.CONNECTED, current().phase());
		assertEquals(ConnectionOutcome.JOINED, current().outcome());
		assertTrue(history().isEmpty(), "history holds finished attempts");
	}

	@Test
	void joiningLogsOneLineWithTheDurations() {
		join(connection);
		ConnectionAttempt attempt = current();
		assertEquals(3 * MS, attempt.resolveNanos());
		assertEquals(2 * MS, attempt.connectNanos());
		assertEquals(1210 * MS, attempt.joinNanos());
		assertEquals(1216 * MS, attempt.totalNanos());
		assertEquals(ConnectionAttempt.UNMEASURED, attempt.authenticateNanos(), "offline mode");
		assertEquals(List.of("Flint Network Core: joined in 1.22 s (resolve 3 ms, connect 2 ms, login+configuration 1.21 s)"), log);
	}

	@Test
	void anOnlineModeJoinTimesAuthentication() {
		openSocket(connection);
		advance(5);
		tracker.authenticating(connection);
		assertEquals(ConnectionPhase.AUTHENTICATING, current().phase());
		advance(500);
		tracker.authenticated(connection);
		assertEquals(ConnectionPhase.AUTHENTICATING, current().phase(), "encryption is part of login");
		advance(20);
		tracker.loginFinished(connection);
		advance(300);
		tracker.connected(connection);
		assertEquals(500 * MS, current().authenticateNanos());
		assertEquals(825 * MS, current().joinNanos());
		assertEquals("Flint Network Core: joined in 831 ms (resolve 3 ms, connect 2 ms, login+configuration 825 ms including authentication 500 ms)",
			log.get(0));
	}

	@Test
	void quittingEndsTheAttemptWhenThePlayListenerCloses() {
		join(connection);
		advance(12 * 60_000 + 3_000);
		tracker.disconnecting(connection, DisconnectReasons.QUITTING);
		assertEquals(ConnectionPhase.DISCONNECTING, current().phase());
		assertEquals(ConnectionOutcome.JOINED, current().outcome(), "not settled yet");
		tracker.closed(connection);
		ConnectionAttempt ended = current();
		assertEquals(ConnectionPhase.DISCONNECTED, ended.phase());
		assertEquals(ConnectionOutcome.QUIT, ended.outcome());
		assertEquals(ConnectionPhase.CONNECTED, ended.reached());
		assertEquals(DisconnectReasons.QUITTING, ended.reasonKey());
		assertEquals(WALL, ended.endedAtMillis());
		assertFalse(tracker.tracks(connection), "the connection isn't held once the attempt is over");
		assertEquals(List.of(ended), history());
		assertEquals("Flint Network Core: disconnected (quit, multiplayer.status.quitting) after 12 min 3 s connected", log.get(1));
	}

	@Test
	void closingWithoutAReasonIsAQuitToo() {
		join(connection);
		tracker.closed(connection);
		assertEquals(ConnectionOutcome.QUIT, current().outcome());
	}

	@Test
	void cancellingWhileResolvingEndsTheAttempt() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		advance(2_100);
		tracker.connectScreenClosed();
		assertEquals(ConnectionOutcome.CANCELLED, current().outcome());
		assertEquals(ConnectionPhase.RESOLVING, current().reached());
		tracker.resolved(CONNECTOR, true);
		assertFalse(tracker.socketOpening(CONNECTOR, connection), "the connector thread finishing late changes nothing");
		assertEquals(ConnectionPhase.DISCONNECTED, current().phase());
		assertEquals(List.of("Flint Network Core: connection cancelled after 2.10 s while resolving"), log);
	}

	@Test
	void cancellingWhileTheSocketConnectsEndsTheAttempt() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, true);
		tracker.socketOpening(CONNECTOR, connection);
		tracker.socketFailed(connection, DisconnectReasons.outcome(new CancellationException()));
		assertEquals(ConnectionOutcome.CANCELLED, current().outcome());
		tracker.connectScreenClosed();
		assertEquals(1, history().size(), "ended once");
	}

	@Test
	void cancellingDuringLoginEndsTheAttemptAtTheDisconnect() {
		openSocket(connection);
		tracker.loginFinished(connection);
		tracker.disconnecting(connection, DisconnectReasons.ABORTED);
		assertEquals(ConnectionOutcome.CANCELLED, current().outcome(), "vanilla never handles this disconnect, so nothing else would end it");
		assertEquals(ConnectionPhase.JOINING, current().reached());
		tracker.connectScreenClosed();
		assertEquals(1, history().size());
	}

	@Test
	void disconnectingFromTheReconfigurationScreenIsAQuit() {
		join(connection);
		tracker.reconfiguring(connection);
		tracker.disconnecting(connection, DisconnectReasons.ABORTED);
		assertEquals(ConnectionOutcome.QUIT, current().outcome(), "that screen's Disconnect sends the connect screen's Cancel reason");
		assertEquals(ConnectionPhase.JOINING, current().reached());
		tracker.disconnected(connection, DisconnectReasons.ABORTED);
		assertEquals(1, history().size());
	}

	@Test
	void theConnectScreenClosingAfterTheSocketOpenedIsNotACancel() {
		openSocket(connection);
		tracker.loginFinished(connection);
		tracker.connectScreenClosed();
		assertTrue(current().running(), "a server's resource pack prompt or dialog replaces the connect screen during configuration");
	}

	@Test
	void aCancelJustAfterTheServerClosedTheLoginEndsWithTheServersReason() {
		openSocket(connection);
		tracker.kicked(connection, "multiplayer.disconnect.not_whitelisted");
		tracker.disconnecting(connection, "multiplayer.disconnect.not_whitelisted");
		// Cancel's own disconnect finds the channel closed, and with the screen gone nothing ticks the connection.
		tracker.connectScreenClosed();
		assertEquals(ConnectionOutcome.KICKED, current().outcome());
		assertEquals("multiplayer.disconnect.not_whitelisted", current().reasonKey());
		tracker.disconnected(connection, "multiplayer.disconnect.not_whitelisted");
		assertEquals(1, history().size(), "a later report changes nothing");
	}

	@Test
	void theConnectScreenClosingDoesNotEndASessionInPlay() {
		join(connection);
		tracker.disconnecting(connection, "disconnect.timeout");
		tracker.connectScreenClosed();
		assertTrue(current().running(), "the play session's own disconnect handling ends it");
	}

	@Test
	void anUnknownHostEndsAtTheLookup() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		advance(4);
		tracker.resolved(CONNECTOR, false);
		assertEquals(ConnectionOutcome.UNKNOWN_HOST, current().outcome());
		assertEquals(DisconnectReasons.UNKNOWN_HOST, current().reasonKey());
		tracker.failedWithoutConnection(null, "disconnect.genericReason");
		assertEquals(ConnectionOutcome.UNKNOWN_HOST, current().outcome(), "the screen that follows changes nothing");
		assertEquals(List.of("Flint Network Core: connection failed (unknown host, disconnect.unknownHost) after 4 ms while resolving"), log);
	}

	@Test
	void aRefusedSocketEndsWhileOpening() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, true);
		tracker.socketOpening(CONNECTOR, connection);
		advance(4);
		tracker.socketFailed(connection, DisconnectReasons.outcome(new ConnectException("Connection refused: /127.0.0.1:25597")));
		assertEquals(ConnectionOutcome.REFUSED, current().outcome());
		assertEquals(List.of("Flint Network Core: connection failed (refused) after 4 ms while opening"), log);
	}

	@Test
	void aSocketConnectTimeoutIsItsOwnOutcome() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, true);
		tracker.socketOpening(CONNECTOR, connection);
		advance(30_000);
		tracker.socketFailed(connection, DisconnectReasons.outcome(new io.netty.channel.ConnectTimeoutException("connection timed out")));
		assertEquals(ConnectionOutcome.CONNECT_TIMEOUT, current().outcome());
		assertEquals("Flint Network Core: connection failed (connect timeout) after 30.00 s while opening", log.get(0));
	}

	@Test
	void aConnectorFailureShownOnlyAsAScreenEndsTheAttempt() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.failedWithoutConnection(null, "disconnect.genericReason");
		assertEquals(ConnectionOutcome.NETWORK_ERROR, current().outcome());
		assertEquals(ConnectionPhase.RESOLVING, current().reached());
	}

	@Test
	void aSocketFailureSeenFirstByTheScreenKeepsItsCause() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, true);
		tracker.socketOpening(CONNECTOR, connection);
		tracker.failedWithoutConnection(ConnectionOutcome.REFUSED, "disconnect.genericReason");
		assertEquals(ConnectionOutcome.REFUSED, current().outcome());
		tracker.socketFailed(connection, ConnectionOutcome.REFUSED);
		assertEquals(1, history().size(), "the listener arriving second changes nothing");
	}

	@Test
	void aDisconnectedScreenDuringLoginIsLeftToTheLoginListener() {
		openSocket(connection);
		tracker.authenticating(connection);
		tracker.failedWithoutConnection(null, "disconnect.genericReason");
		assertTrue(current().running());
	}

	@Test
	void aReadTimeoutIsTimedOut() {
		join(connection);
		tracker.disconnecting(connection, "disconnect.timeout");
		tracker.disconnected(connection, "disconnect.timeout");
		assertEquals(ConnectionOutcome.TIMED_OUT, current().outcome());
	}

	@Test
	void theServersKeepAliveTimeoutIsTimedOutNotKicked() {
		join(connection);
		tracker.kicked(connection, "disconnect.timeout");
		tracker.disconnecting(connection, "disconnect.timeout");
		tracker.disconnected(connection, "disconnect.timeout");
		assertEquals(ConnectionOutcome.TIMED_OUT, current().outcome());
	}

	@Test
	void aKickIsKicked() {
		join(connection);
		advance(60_000);
		tracker.kicked(connection, "multiplayer.disconnect.kicked");
		tracker.disconnecting(connection, "multiplayer.disconnect.kicked");
		tracker.disconnected(connection, "multiplayer.disconnect.kicked");
		assertEquals(ConnectionOutcome.KICKED, current().outcome());
		assertEquals("multiplayer.disconnect.kicked", current().reasonKey());
		tracker.closed(connection);
		assertEquals(ConnectionOutcome.KICKED, current().outcome(), "Minecraft closes the play listener after the disconnect; that's not a quit");
		assertEquals(1, history().size());
		assertEquals("Flint Network Core: disconnected (kicked, multiplayer.disconnect.kicked) after 1 min 0 s connected", log.get(1));
	}

	@Test
	void aKickBorrowingTheCancelReasonIsAKickNotACancel() {
		openSocket(connection);
		tracker.kicked(connection, DisconnectReasons.ABORTED);
		tracker.disconnecting(connection, DisconnectReasons.ABORTED);
		assertEquals(ConnectionOutcome.KICKED, current().outcome(), "the server's packet, not the player's Cancel");
		assertEquals(DisconnectReasons.ABORTED, current().reasonKey());
		Object next = new Object();
		join(next);
		tracker.kicked(next, DisconnectReasons.QUITTING);
		tracker.disconnecting(next, DisconnectReasons.QUITTING);
		tracker.disconnected(next, DisconnectReasons.QUITTING);
		assertEquals(ConnectionOutcome.KICKED, current().outcome(), "not the player quitting");
		assertEquals(2, history().size());
	}

	@Test
	void aKickWithTheServersOwnTextKeepsNoText() {
		join(connection);
		tracker.kicked(connection, "Banned by an operator at 203.0.113.7");
		tracker.disconnecting(connection, null);
		tracker.disconnected(connection, null);
		assertEquals(ConnectionOutcome.KICKED, current().outcome());
		assertNull(current().reasonKey());
	}

	@Test
	void aKickDuringLoginEndsWhileLoggingIn() {
		openSocket(connection);
		advance(7);
		tracker.kicked(connection, "multiplayer.disconnect.not_whitelisted");
		tracker.disconnecting(connection, "multiplayer.disconnect.not_whitelisted");
		tracker.disconnected(connection, "multiplayer.disconnect.not_whitelisted");
		assertEquals(ConnectionOutcome.KICKED, current().outcome());
		assertEquals(ConnectionPhase.OPENING, current().reached());
		assertEquals("Flint Network Core: connection failed (kicked, multiplayer.disconnect.not_whitelisted) after 13 ms while logging in", log.get(0));
	}

	@Test
	void theServerClosingTheSocketIsServerClosed() {
		join(connection);
		// The channel was already closed, so vanilla stored no reason and reports the generic one.
		tracker.disconnected(connection, DisconnectReasons.GENERIC);
		assertEquals(ConnectionOutcome.SERVER_CLOSED, current().outcome());
	}

	@Test
	void aServerShutdownIsServerClosed() {
		join(connection);
		tracker.kicked(connection, "multiplayer.disconnect.server_shutdown");
		tracker.disconnecting(connection, "multiplayer.disconnect.server_shutdown");
		tracker.disconnected(connection, "multiplayer.disconnect.server_shutdown");
		assertEquals(ConnectionOutcome.SERVER_CLOSED, current().outcome());
	}

	@Test
	void theGenericReasonDoesNotHideAnEarlierOne() {
		join(connection);
		tracker.disconnecting(connection, "disconnect.timeout");
		tracker.disconnected(connection, DisconnectReasons.GENERIC);
		assertEquals(ConnectionOutcome.TIMED_OUT, current().outcome());
	}

	@Test
	void aFailedSessionCheckIsAuthFailed() {
		openSocket(connection);
		tracker.authenticating(connection);
		advance(400);
		tracker.disconnecting(connection, "disconnect.loginFailedInfo.invalidSession");
		tracker.disconnected(connection, "disconnect.loginFailedInfo.invalidSession");
		assertEquals(ConnectionOutcome.AUTH_FAILED, current().outcome());
		assertEquals(ConnectionPhase.AUTHENTICATING, current().reached());
		assertEquals("Flint Network Core: connection failed (authentication failed, disconnect.loginFailedInfo.invalidSession) after 406 ms while authenticating",
			log.get(0));
	}

	@Test
	void aTransferEndsTheSessionAndTheNextAttemptFollows() {
		join(connection);
		tracker.disconnecting(connection, "disconnect.transfer");
		tracker.disconnected(connection, "disconnect.transfer");
		assertEquals(ConnectionOutcome.TRANSFERRED, current().outcome());
		Object next = new Object();
		join(next);
		assertEquals(2, current().id());
		assertEquals(ConnectionPhase.CONNECTED, current().phase());
	}

	@Test
	void reconfigurationStaysInTheSameAttempt() {
		join(connection);
		long total = current().totalNanos();
		advance(5_000);
		tracker.reconfiguring(connection);
		assertEquals(ConnectionPhase.JOINING, current().phase());
		assertEquals(ConnectionOutcome.JOINED, current().outcome());
		advance(800);
		tracker.connected(connection);
		assertEquals(ConnectionPhase.CONNECTED, current().phase());
		assertEquals(1, current().reconfigurations());
		assertEquals(total, current().totalNanos(), "durations stay those of the first join");
		assertEquals(5_800 * MS, current().connectedNanos(clock.get()), "connected time counts from the first join");
		assertEquals(1, log.size(), "one joined line");
		assertEquals(1, current().id());
	}

	@Test
	void eventsFromAnotherConnectionAreIgnored() {
		Object singleplayer = new Object();
		tracker.connected(singleplayer);
		tracker.closed(singleplayer);
		assertEquals(ConnectionPhase.IDLE, current().phase(), "nothing tracked yet");
		join(connection);
		tracker.disconnecting(singleplayer, DisconnectReasons.QUITTING);
		tracker.disconnected(singleplayer, "multiplayer.disconnect.kicked");
		tracker.closed(singleplayer);
		assertEquals(ConnectionPhase.CONNECTED, current().phase());
		assertTrue(log.size() == 1 && history().isEmpty());
	}

	@Test
	void eventsFromAnOldConnectionAreIgnoredByTheNextAttempt() {
		join(connection);
		tracker.closed(connection);
		Object next = new Object();
		openSocket(next);
		tracker.connected(connection);
		tracker.disconnected(connection, "multiplayer.disconnect.kicked");
		tracker.kicked(connection, "multiplayer.disconnect.kicked");
		tracker.socketFailed(connection, ConnectionOutcome.REFUSED);
		assertEquals(2, current().id());
		assertEquals(ConnectionPhase.OPENING, current().phase());
		assertTrue(current().running());
		assertFalse(tracker.tracks(connection));
		assertTrue(tracker.tracks(next));
	}

	@Test
	void onlyTheTrackedConnectorThreadCounts() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(OTHER_THREAD);
		assertEquals(ConnectionPhase.CONNECTING, current().phase(), "a server-list ping's lookup");
		tracker.resolving(CONNECTOR);
		tracker.resolved(OTHER_THREAD, false);
		assertEquals(ConnectionPhase.RESOLVING, current().phase());
		tracker.resolved(CONNECTOR, true);
		assertFalse(tracker.socketOpening(OTHER_THREAD, new Object()), "a ping's socket");
		assertTrue(tracker.socketOpening(CONNECTOR, connection));
		assertFalse(tracker.socketOpening(CONNECTOR, new Object()), "only one socket per attempt");
		assertTrue(tracker.tracks(connection));
	}

	@Test
	void anOldConnectorThreadDoesNotReachTheNextAttempt() {
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.connectScreenClosed();
		tracker.attemptStarted(OTHER_THREAD);
		tracker.resolved(CONNECTOR, false);
		assertFalse(tracker.socketOpening(CONNECTOR, connection));
		assertEquals(ConnectionPhase.CONNECTING, current().phase());
		assertEquals(2, current().id());
	}

	@Test
	void aNewAttemptEndsOneStillRunning() {
		join(connection);
		tracker.attemptStarted(OTHER_THREAD);
		assertEquals(2, current().id());
		ConnectionAttempt previous = history().get(0);
		assertEquals(1, previous.id());
		assertEquals(ConnectionOutcome.UNKNOWN, previous.outcome());
		assertTrue(log.get(1).startsWith("Flint Network Core: disconnected (unknown) after "), log.get(1));
	}

	@Test
	void theHistoryStaysNewestFirstWhenEndingsArriveOutOfOrder() {
		ConnectionDiagnostics diagnostics = new ConnectionDiagnostics(3);
		ConnectionAttempt[] ended = new ConnectionAttempt[5];
		ConnectionAttempt previous = ConnectionAttempt.IDLE;
		for (int i = 0; i < ended.length; i++) {
			previous = ConnectionAttempt.start(previous, CONNECTOR, 0).end(ConnectionOutcome.UNKNOWN_HOST, null, 1, WALL);
			ended[i] = previous;
		}
		diagnostics.add(ended[0]);
		diagnostics.add(ended[2]);
		diagnostics.add(ended[1]);
		assertEquals(List.of(ended[2], ended[1], ended[0]), diagnostics.history(), "two threads ended 2 and 3 and added them in the other order");
		diagnostics.add(ended[4]);
		diagnostics.add(ended[3]);
		assertEquals(List.of(ended[4], ended[3], ended[2]), diagnostics.history());
		diagnostics.add(ended[0]);
		assertEquals(List.of(ended[4], ended[3], ended[2]), diagnostics.history(), "older than everything kept");
	}

	@Test
	void theHistoryKeepsTheLastEightNewestFirst() {
		for (int i = 0; i < 20; i++) {
			tracker.attemptStarted(CONNECTOR);
			tracker.resolving(CONNECTOR);
			tracker.resolved(CONNECTOR, false);
		}
		List<ConnectionAttempt> history = history();
		assertEquals(ConnectionDiagnostics.CAPACITY, history.size());
		for (int i = 0; i < history.size(); i++) {
			assertEquals(20 - i, history.get(i).id());
			assertEquals(ConnectionOutcome.UNKNOWN_HOST, history.get(i).outcome());
		}
		assertEquals(20, log.size(), "one line per attempt");
	}

	@Test
	void theDeveloperLogAddsALinePerPhaseChange() {
		tracker.logTransitions(true);
		join(connection);
		tracker.disconnected(connection, "multiplayer.disconnect.kicked");
		assertEquals(List.of(
			"Flint Network Core: attempt 1 starting",
			"Flint Network Core: attempt 1 starting -> resolving after 1 ms",
			"Flint Network Core: attempt 1 resolving -> opening after 3 ms",
			"Flint Network Core: attempt 1 opening -> joining after 12 ms",
			"Flint Network Core: attempt 1 joining -> connected after 1.20 s",
			"Flint Network Core: joined in 1.22 s (resolve 3 ms, connect 2 ms, login+configuration 1.21 s)",
			"Flint Network Core: attempt 1 connected -> disconnected after <1 ms (kicked)",
			"Flint Network Core: disconnected (kicked, multiplayer.disconnect.kicked) after <1 ms connected"
		), log);
	}

	@Test
	void resetEndsARunningAttemptQuietly() {
		join(connection);
		tracker.reset();
		assertFalse(current().running());
		assertFalse(tracker.tracks(connection));
		assertEquals(1, log.size(), "only the joined line");
		assertTrue(history().isEmpty());
	}

	@Test
	void noLineCarriesAHostAnAddressOrServerText() {
		String serverText = "You were kicked from play.example.net (203.0.113.7:25565)";
		join(connection);
		tracker.kicked(connection, serverText);
		tracker.disconnecting(connection, serverText);
		tracker.disconnected(connection, serverText);
		openSocket(new Object());
		tracker.failedWithoutConnection(null, "Connection refused: /203.0.113.7:25565");
		tracker.attemptStarted(CONNECTOR);
		tracker.resolving(CONNECTOR);
		tracker.resolved(CONNECTOR, false);
		tracker.logTransitions(true);
		join(new Object());
		assertTrue(log.size() > 4);
		for (String line : log) {
			assertTrue(line.startsWith(NetworkLog.PREFIX), line);
			assertFalse(IP.matcher(line).find(), line);
			assertFalse(line.contains("example") || line.contains("25565") || line.contains("kicked from") || line.contains("refused:"), line);
		}
		for (ConnectionAttempt attempt : history()) {
			String key = attempt.reasonKey();
			assertTrue(key == null || DisconnectReasons.vanillaKey(key) != null, key);
		}
	}

	@Test
	void concurrentEndingsEndAnAttemptExactlyOnce() throws Exception {
		for (int round = 0; round < 200; round++) {
			Object socket = new Object();
			join(socket);
			CyclicBarrier start = new CyclicBarrier(4);
			List<Thread> threads = List.of(
				racer(start, () -> tracker.disconnected(socket, "multiplayer.disconnect.kicked")),
				racer(start, () -> tracker.closed(socket)),
				racer(start, () -> tracker.disconnecting(socket, DisconnectReasons.QUITTING)),
				racer(start, () -> tracker.disconnected(socket, DisconnectReasons.GENERIC)));
			for (Thread thread : threads) {
				thread.join();
			}
			assertFalse(current().running());
			assertEquals(round + 1, current().id());
		}
		long ended = log.stream().filter(line -> line.contains("disconnected (")).count();
		assertEquals(200, ended, "exactly one end line per attempt");
		assertEquals(ConnectionDiagnostics.CAPACITY, history().size());
	}

	@Test
	void concurrentEventsAreNeverLost() throws Exception {
		for (int round = 0; round < 500; round++) {
			Object socket = new Object();
			join(socket);
			CyclicBarrier start = new CyclicBarrier(2);
			Thread netty = racer(start, () -> tracker.kicked(socket, null));
			Thread other = racer(start, () -> tracker.disconnecting(socket, null));
			netty.join();
			other.join();
			assertEquals(ConnectionPhase.DISCONNECTING, current().phase(), "the phase change survived");
			tracker.disconnected(socket, null);
			assertEquals(ConnectionOutcome.KICKED, current().outcome(), "the kick survived");
		}
	}

	@Test
	void aCancelRacingTheSocketResultEndsOnce() throws Exception {
		for (int round = 0; round < 300; round++) {
			Object socket = new Object();
			tracker.attemptStarted(CONNECTOR);
			tracker.resolving(CONNECTOR);
			tracker.resolved(CONNECTOR, true);
			tracker.socketOpening(CONNECTOR, socket);
			CyclicBarrier start = new CyclicBarrier(3);
			Thread main = racer(start, tracker::connectScreenClosed);
			Thread netty = racer(start, () -> tracker.socketFailed(socket, ConnectionOutcome.REFUSED));
			Thread connector = racer(start, () -> tracker.socketOpened(socket));
			main.join();
			netty.join();
			connector.join();
			if (current().running()) {
				// The socket opened first, so neither the cancel nor the failure applied.
				assertEquals(ConnectionPhase.OPENING, current().phase());
				tracker.disconnecting(socket, DisconnectReasons.ABORTED);
			}
			assertFalse(current().running());
		}
		assertEquals(300, log.size(), "one line per attempt, never two");
	}

	private static Thread racer(CyclicBarrier start, Runnable event) {
		Thread thread = new Thread(() -> {
			try {
				start.await();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			event.run();
		});
		thread.start();
		return thread;
	}

	@Test
	void theSnapshotOfAFinishedAttemptIsUnchangedByLaterEvents() {
		join(connection);
		tracker.closed(connection);
		ConnectionAttempt ended = current();
		tracker.attemptStarted(CONNECTOR);
		assertSame(ended, history().get(0));
		assertEquals(ConnectionPhase.DISCONNECTED, ended.phase());
		assertEquals(ConnectionOutcome.QUIT, ended.outcome());
	}
}

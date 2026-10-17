package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ConnectTimeoutException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class DisconnectReasonsTest {
	@Test
	void onlyVanillaKeysAreKept() {
		assertEquals("multiplayer.disconnect.kicked", DisconnectReasons.vanillaKey("multiplayer.disconnect.kicked"));
		assertEquals("disconnect.loginFailedInfo.invalidSession", DisconnectReasons.vanillaKey("disconnect.loginFailedInfo.invalidSession"));
		assertNull(DisconnectReasons.vanillaKey(null));
		assertNull(DisconnectReasons.vanillaKey("myplugin.kick.reason"), "a plugin's key");
		assertNull(DisconnectReasons.vanillaKey("multiplayer.disconnect.203.0.113.7"), "vanilla-looking, but not vanilla");
		assertNull(DisconnectReasons.vanillaKey("You have been kicked"), "server text");
		assertNull(DisconnectReasons.vanillaKey("connect.failed"), "a screen title, not a reason");
	}

	@Test
	void reasonKeysMapToOutcomes() {
		assertEquals(ConnectionOutcome.CANCELLED, DisconnectReasons.outcome("connect.aborted", false));
		assertEquals(ConnectionOutcome.QUIT, DisconnectReasons.outcome("multiplayer.status.quitting", false));
		assertEquals(ConnectionOutcome.QUIT, DisconnectReasons.outcome("multiplayer.requiredTexturePrompt.disconnect", false));
		assertEquals(ConnectionOutcome.UNKNOWN_HOST, DisconnectReasons.outcome("disconnect.unknownHost", false));
		assertEquals(ConnectionOutcome.TIMED_OUT, DisconnectReasons.outcome("disconnect.timeout", false));
		assertEquals(ConnectionOutcome.SERVER_CLOSED, DisconnectReasons.outcome("multiplayer.disconnect.server_shutdown", false));
		assertEquals(ConnectionOutcome.SERVER_CLOSED, DisconnectReasons.outcome("multiplayer.disconnect.generic", false));
		assertEquals(ConnectionOutcome.SERVER_CLOSED, DisconnectReasons.outcome("disconnect.endOfStream", false));
		assertEquals(ConnectionOutcome.AUTH_FAILED, DisconnectReasons.outcome("disconnect.loginFailedInfo", false));
		assertEquals(ConnectionOutcome.AUTH_FAILED, DisconnectReasons.outcome("disconnect.loginFailedInfo.invalidSession", false));
		assertEquals(ConnectionOutcome.AUTH_FAILED, DisconnectReasons.outcome("disconnect.loginFailedInfo.serversUnavailable", false));
		assertEquals(ConnectionOutcome.AUTH_FAILED, DisconnectReasons.outcome("multiplayer.disconnect.unverified_username", true));
		assertEquals(ConnectionOutcome.TRANSFERRED, DisconnectReasons.outcome("disconnect.transfer", false));
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome("disconnect.genericReason", false));
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome("disconnect.packetError", false));
		assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome("multiplayer.disconnect.kicked", false));
		assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome("multiplayer.disconnect.not_whitelisted", false));
		assertEquals(ConnectionOutcome.UNKNOWN, DisconnectReasons.outcome(null, false));
		assertEquals(ConnectionOutcome.UNKNOWN, DisconnectReasons.outcome("someone.elses.key", false));
	}

	@Test
	void aKickNamesTheOutcomeUnlessTheKeySaysMore() {
		assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome(null, true), "a kick in the server's own words");
		assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome("multiplayer.disconnect.generic", true));
		assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome("disconnect.genericReason", true), "the server's own error");
		assertEquals(ConnectionOutcome.TIMED_OUT, DisconnectReasons.outcome("disconnect.timeout", true), "the server's keep-alive timeout");
		assertEquals(ConnectionOutcome.SERVER_CLOSED, DisconnectReasons.outcome("multiplayer.disconnect.server_shutdown", true));
	}

	@Test
	void aServerBorrowingTheClientsOwnReasonIsStillAKick() {
		for (String key : new String[] {"connect.aborted", "multiplayer.status.quitting", "multiplayer.requiredTexturePrompt.disconnect",
			"disconnect.transfer", "disconnect.unknownHost"}) {
			assertEquals(ConnectionOutcome.KICKED, DisconnectReasons.outcome(key, true), key);
		}
	}

	@Test
	void wrappersAreRecognised() {
		assertTrue(DisconnectReasons.wrapsReason("disconnect.genericReason"));
		assertTrue(DisconnectReasons.wrapsReason("disconnect.loginFailedInfo"));
		assertFalse(DisconnectReasons.wrapsReason("disconnect.timeout"));
	}

	@Test
	void socketFailuresAreCategorisedByClass() {
		assertEquals(ConnectionOutcome.REFUSED, DisconnectReasons.outcome(new ConnectException("Connection refused")));
		assertEquals(ConnectionOutcome.REFUSED, DisconnectReasons.outcome(new ConnectException("connect(..) failed: Network is unreachable")),
			"the ConnectException family, whatever the message");
		assertEquals(ConnectionOutcome.CONNECT_TIMEOUT, DisconnectReasons.outcome(new ConnectTimeoutException("connection timed out after 30000 ms")),
			"Netty's timeout is a ConnectException too");
		assertEquals(ConnectionOutcome.CONNECT_TIMEOUT, DisconnectReasons.outcome(new SocketTimeoutException()));
		assertEquals(ConnectionOutcome.CANCELLED, DisconnectReasons.outcome(new CancellationException()));
		assertEquals(ConnectionOutcome.UNKNOWN_HOST, DisconnectReasons.outcome(new UnknownHostException("flint-qa.invalid")));
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome(new NoRouteToHostException()));
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome(new IOException("Connection reset")));
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome((Throwable) null));
	}

	@Test
	void wrappedSocketFailuresAreFound() {
		assertEquals(ConnectionOutcome.REFUSED, DisconnectReasons.outcome(new CompletionException(new ConnectException("refused"))));
		assertEquals(ConnectionOutcome.CONNECT_TIMEOUT,
			DisconnectReasons.outcome(new RuntimeException(new IllegalStateException(new ConnectTimeoutException("timed out")))));
	}

	@Test
	void aCauseLoopEnds() {
		RuntimeException first = new RuntimeException("first");
		RuntimeException second = new RuntimeException("second", first);
		first.initCause(second);
		assertEquals(ConnectionOutcome.NETWORK_ERROR, DisconnectReasons.outcome(first));
	}
}

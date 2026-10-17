package com.flintclient.client.network;

/** How an attempt went. Only categories: never the server's own text. */
public enum ConnectionOutcome {
	/** Reached play and still connected. */
	JOINED("joined"),
	/** The player left (quit to the menu, or declined a required resource pack). */
	QUIT("quit"),
	/** The player pressed Cancel on the connect screen. */
	CANCELLED("cancelled"),
	/** The address didn't resolve (or is on the blocklist, which vanilla reports the same way). */
	UNKNOWN_HOST("unknown host"),
	/** The socket connect failed outright: refused, unreachable. */
	REFUSED("refused"),
	/** The socket connect didn't finish in time. */
	CONNECT_TIMEOUT("connect timeout"),
	/** The server went silent: the client's read timeout, or the server's keep-alive timeout. */
	TIMED_OUT("timed out"),
	/** The server sent a disconnect with its own reason. */
	KICKED("kicked"),
	/** The server shut down or closed the socket without a reason. */
	SERVER_CLOSED("server closed"),
	/** Session authentication failed on either side. */
	AUTH_FAILED("authentication failed"),
	/** The server sent the client on to another server (a new attempt follows). */
	TRANSFERRED("transferred"),
	/** Any other exception on the connection. */
	NETWORK_ERROR("network error"),
	UNKNOWN("unknown");

	private final String label;

	ConnectionOutcome(String label) {
		this.label = label;
	}

	/** For the log and a future HUD. */
	public String label() {
		return label;
	}
}

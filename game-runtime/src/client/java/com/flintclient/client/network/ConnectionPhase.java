package com.flintclient.client.network;

/** Where a multiplayer connection attempt is. The order is the order an attempt goes through them. */
public enum ConnectionPhase {
	/** No attempt yet this session. */
	IDLE("idle"),
	/** The connect screen has started an attempt; its connector thread hasn't begun the lookup yet. */
	CONNECTING("starting"),
	/** Looking the address up (A/AAAA, and SRV on the default port). */
	RESOLVING("resolving"),
	/** Opening the socket, then the start of login until the server asks for authentication or finishes login. */
	OPENING("opening"),
	/** The server asked for session authentication (online mode). */
	AUTHENTICATING("authenticating"),
	/** Login finished: configuration until play starts, and again whenever the server reconfigures. */
	JOINING("joining"),
	/** Playing. */
	CONNECTED("connected"),
	/** The connection is closing; the outcome isn't settled yet. */
	DISCONNECTING("disconnecting"),
	/** The attempt is over and its outcome is final. */
	DISCONNECTED("disconnected");

	private final String word;

	ConnectionPhase(String word) {
		this.word = word;
	}

	/** For the log: "while resolving". */
	public String word() {
		return word;
	}

	/** A step forward rather than closing: the phase an attempt counts as having reached. */
	boolean progress() {
		return this != IDLE && this != DISCONNECTING && this != DISCONNECTED;
	}
}

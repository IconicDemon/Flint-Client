package com.flintclient.client.control.model;

import org.jspecify.annotations.Nullable;

/**
 * Whether a module has something to show, and if not, why. An unavailable module stays togglable: the reason
 * is shown next to it (in words, with an icon, never colour alone) so turning it on is never a mystery.
 */
public enum Availability {
	AVAILABLE(null, null),
	NEEDS_WORLD("Shows in a world", "NEEDS A WORLD"),
	SERVERS_ONLY("Shows on multiplayer servers", "SERVERS ONLY"),
	NETWORK_CORE_OFF("Flint Network Core is off", "NETWORK CORE OFF"),
	WAITING_FOR_SERVER("Waiting for the server to report it", "WAITING FOR SERVER");

	private final @Nullable String reason;
	private final @Nullable String status;

	Availability(@Nullable String reason, @Nullable String status) {
		this.reason = reason;
		this.status = status;
	}

	public boolean available() {
		return this == AVAILABLE;
	}

	/** A short sentence for the settings panel, or null when available. */
	public @Nullable String reason() {
		return reason;
	}

	/** The card's CAPS status line, or null when available (the card then shows ON/OFF). */
	public @Nullable String status() {
		return status;
	}
}

package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * Your latency, as the server reports it in the player list (the number behind the bars on Tab): the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Ping extends HudBackend {
	public Ping() {
		super("ping", "ping");
	}
}

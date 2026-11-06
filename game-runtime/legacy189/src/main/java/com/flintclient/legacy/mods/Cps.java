package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * Clicks per second, from the clicks that reach the game: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Cps extends HudBackend {
	public Cps() {
		super("cps", "cps");
	}
}

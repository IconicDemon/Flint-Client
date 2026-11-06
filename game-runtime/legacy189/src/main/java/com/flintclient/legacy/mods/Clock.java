package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * The real time on your computer's clock, its date, and the world's time of day: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Clock extends HudBackend {
	public Clock() {
		super("clock", "time");
	}
}

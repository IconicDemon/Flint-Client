package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * The world's day as Minecraft counts it: "DAY 12" in white with Minecraft's shadow, where the action bar shows: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class DayCounter extends HudBackend {
	public DayCounter() {
		super("day_counter", "day_counter");
	}
}

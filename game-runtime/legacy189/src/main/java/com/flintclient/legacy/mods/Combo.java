package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * Hits you land in a row without being hit back (the shared combat tracker, fed by Combat): the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Combo extends HudBackend {
	public Combo() {
		super("combo_counter", "combodisplay");
	}
}

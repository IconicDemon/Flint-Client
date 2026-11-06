package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * The movement keys, the mouse buttons and jump, lit while held, with clicks per second: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Keystrokes extends HudBackend {
	public Keystrokes() {
		super("keystrokes", "keystrokes");
	}
}

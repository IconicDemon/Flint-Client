package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * How many frames the game drew in the last second, from the frame times Flint records (as the FPS module reads them on every line): the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Fps extends HudBackend {
	public Fps() {
		super("fps", "fps");
	}
}

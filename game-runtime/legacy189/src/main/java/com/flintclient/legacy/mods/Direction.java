package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * The way you are facing: a compass point, its degrees and axis, or a compass strip with your waypoints on it: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Direction extends HudBackend {
	public Direction() {
		super("direction", "direction");
	}
}

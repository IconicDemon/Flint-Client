package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;

/**
 * Your position in the world, the way you face, and the chunk, biome and other dimension lines: the 1.8.9 backend of the shared module, drawn by the shared HUD as on every line (its view reads
 * {@code platform.HudData}).
 */
public final class Coordinates extends HudBackend {
	public Coordinates() {
		super("coordinates", "coordinates", "copyCoordinates");
	}
}

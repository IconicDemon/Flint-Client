package com.flintclient.client.platform;

import com.flintclient.client.control.platform.Mc263Features;
import org.jspecify.annotations.Nullable;

/**
 * Where the player is, for the waypoint list: the dimension, the position, the block stood on, and a waypoint added there.
 * Platform class (see {@link Clock}). Render thread.
 */
public final class World {
	private World() {
	}

	/** The dimension's id ({@code minecraft:overworld}), or null outside a world. */
	public static @Nullable String dimension() {
		return Mc263Features.dimension();
	}

	/** The player's position, or null outside a world. */
	public static double @Nullable [] position() {
		return Mc263Features.position();
	}

	/** The block the player stands on, or null outside a world. */
	public static int @Nullable [] blockPosition() {
		return Mc263Features.blockPosition();
	}

	/** Adds a waypoint called {@code name} in {@code rgb} where the player stands; its index, or -1 outside a world. */
	public static int addWaypointHere(String name, int rgb) {
		return Mc263Features.addWaypointHere(name, rgb);
	}

	/** The colour the next waypoint gets (Waypoints' random colours, or its one colour). */
	public static int nextWaypointColor() {
		return Mc263Features.nextWaypointColor();
	}
}

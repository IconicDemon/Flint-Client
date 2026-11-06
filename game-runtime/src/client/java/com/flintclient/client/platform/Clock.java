package com.flintclient.client.platform;

import net.minecraft.util.Util;

/**
 * The time Flint's screens animate by: Minecraft's own monotonic clock. One of the platform classes (this package): the
 * shared menu, kit and canvas reach the game only through them, and each engine has its own file of the same name (26.x and
 * 1.21.x here, 1.8.9 in src/mc189/java), so the shared code compiles unchanged on every line (docs/LEGACY189_SHARED_MENU_PLAN.md).
 */
public final class Clock {
	private Clock() {
	}

	/** Milliseconds on a monotonic clock (only differences mean anything). */
	public static long millis() {
		return Util.getMillis();
	}

	/** Nanoseconds on the same clock. */
	public static long nanos() {
		return Util.getNanos();
	}
}

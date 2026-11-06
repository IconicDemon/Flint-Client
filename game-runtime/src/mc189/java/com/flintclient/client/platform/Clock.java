package com.flintclient.client.platform;

/**
 * The time Flint's screens animate by, on Minecraft 1.8.9: the JVM's monotonic clock (1.8.9's own {@code Minecraft.getTime}
 * reads LWJGL 2's timer, which is coarser). The platform classes of 1.8.9 (this folder) stand in for the 26.x ones of the same
 * names (src/client/java/.../platform), so the shared menu, kit and canvas compile unchanged here.
 */
public final class Clock {
	private Clock() {
	}

	/** Milliseconds on a monotonic clock (only differences mean anything). */
	public static long millis() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Nanoseconds on the same clock. */
	public static long nanos() {
		return System.nanoTime();
	}
}

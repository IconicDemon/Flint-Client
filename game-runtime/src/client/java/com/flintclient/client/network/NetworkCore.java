package com.flintclient.client.network;

import com.flintclient.client.FlintClient;

/**
 * Flint Network Core for this session: the one {@link ConnectionTracker}, its history and the pull API.
 * Off until the {@code networkCore} module starts it; the hooks check {@link #active()} first, so while it's
 * off they cost one volatile read. If a hook throws, {@link #fail} switches it off for the rest of the
 * session and Minecraft carries on.
 */
public final class NetworkCore {
	private static final ConnectionTracker TRACKER = new ConnectionTracker(System::nanoTime, System::currentTimeMillis,
		new ConnectionDiagnostics(), FlintClient.LOGGER::info);
	private static volatile NetworkStats stats = new NetworkStats(TRACKER, NetworkPlatform.NONE);
	private static volatile boolean active;

	private NetworkCore() {
	}

	/** From the module's start, with this Minecraft version's live readings. */
	public static void start(NetworkPlatform platform) {
		stats = new NetworkStats(TRACKER, platform);
		active = true;
	}

	/** Developer log ({@code dev.network}): every phase change too. */
	public static void logTransitions() {
		TRACKER.logTransitions(true);
	}

	public static boolean active() {
		return active;
	}

	public static ConnectionTracker tracker() {
		return TRACKER;
	}

	/** The pull API (main thread). */
	public static NetworkStats stats() {
		return stats;
	}

	/** From a hook that threw, on any thread: off for the session, and the attempt in progress is dropped. */
	public static synchronized void fail(RuntimeException e) {
		if (!active) {
			return;
		}
		active = false;
		TRACKER.reset();
		FlintClient.LOGGER.warn("Flint Network Core failed; it's off for the rest of this session", e);
	}
}

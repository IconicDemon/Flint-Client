package com.flintclient.client.network;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The pull API for a HUD or diagnostics screen. Reading asks the server nothing and sends no packet: the
 * latency is the value the server already reports for the local player, and the packet rates are
 * Minecraft's own one-second averages. {@link #snapshot()} allocates only the snapshot; the single-value
 * getters allocate nothing, for use every frame. Main thread.
 */
public final class NetworkStats {
	private static final long NANOS_PER_MILLI = 1_000_000L;

	private final ConnectionTracker tracker;
	private final NetworkPlatform platform;

	public NetworkStats(ConnectionTracker tracker, NetworkPlatform platform) {
		this.tracker = tracker;
		this.platform = platform;
	}

	public NetworkSnapshot snapshot() {
		ConnectionAttempt attempt = tracker.current();
		Object connection = live(attempt);
		return new NetworkSnapshot(attempt.phase(), connectedMillis(attempt),
			connection == null ? NetworkPlatform.UNKNOWN : platform.latencyMillis(connection),
			connection == null ? NetworkPlatform.UNKNOWN : platform.packetsReceivedPerSecond(connection),
			connection == null ? NetworkPlatform.UNKNOWN : platform.packetsSentPerSecond(connection),
			attempt);
	}

	public ConnectionPhase phase() {
		return tracker.current().phase();
	}

	/** Time in play so far, or -1 when not connected. */
	public long connectedMillis() {
		return connectedMillis(tracker.current());
	}

	/** Server-reported latency, or {@link NetworkPlatform#UNKNOWN}. */
	public int latencyMillis() {
		Object connection = live(tracker.current());
		return connection == null ? NetworkPlatform.UNKNOWN : platform.latencyMillis(connection);
	}

	public float packetsReceivedPerSecond() {
		Object connection = live(tracker.current());
		return connection == null ? NetworkPlatform.UNKNOWN : platform.packetsReceivedPerSecond(connection);
	}

	public float packetsSentPerSecond() {
		Object connection = live(tracker.current());
		return connection == null ? NetworkPlatform.UNKNOWN : platform.packetsSentPerSecond(connection);
	}

	/** The last {@value ConnectionDiagnostics#CAPACITY} finished attempts, newest first. */
	public List<ConnectionAttempt> history() {
		return tracker.diagnostics().history();
	}

	private static @Nullable Object live(ConnectionAttempt attempt) {
		return attempt.phase() == ConnectionPhase.CONNECTED ? attempt.connection() : null;
	}

	private long connectedMillis(ConnectionAttempt attempt) {
		return attempt.phase() == ConnectionPhase.CONNECTED ? attempt.connectedNanos(tracker.now()) / NANOS_PER_MILLI : -1L;
	}
}

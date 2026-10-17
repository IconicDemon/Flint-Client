package com.flintclient.client.network;

/**
 * The version seam for the live numbers Minecraft already keeps about a connection. Each one is passed the
 * tracked connection and answers {@link #UNKNOWN} unless that is the connection Minecraft is playing on, so
 * singleplayer's in-memory connection never shows up. Reading sends nothing. Main thread.
 */
public interface NetworkPlatform {
	int UNKNOWN = -1;

	/** For before the Network Core starts, and for tests. */
	NetworkPlatform NONE = new NetworkPlatform() {
		@Override
		public int latencyMillis(Object connection) {
			return UNKNOWN;
		}

		@Override
		public float packetsReceivedPerSecond(Object connection) {
			return UNKNOWN;
		}

		@Override
		public float packetsSentPerSecond(Object connection) {
			return UNKNOWN;
		}
	};

	/**
	 * The local player's latency as the server reports it: the server's smoothed keep-alive round trip,
	 * sent about every 30 s. {@link #UNKNOWN} when the server hasn't reported one.
	 */
	int latencyMillis(Object connection);

	/** Minecraft's own smoothed count of packets received per second (as on the F3 network chart). */
	float packetsReceivedPerSecond(Object connection);

	float packetsSentPerSecond(Object connection);
}

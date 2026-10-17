package com.flintclient.client.network;

/**
 * What {@link NetworkStats#snapshot()} saw at one moment. Live readings are {@link NetworkPlatform#UNKNOWN}
 * unless the phase is {@link ConnectionPhase#CONNECTED}.
 *
 * @param connectedMillis time in play since the attempt first reached it, or -1
 * @param latencyMillis server-reported latency (the server's keep-alive average, refreshed about every 30 s)
 * @param lastAttempt the latest attempt (running or finished), for its durations, outcome and reason key
 */
public record NetworkSnapshot(
	ConnectionPhase phase,
	long connectedMillis,
	int latencyMillis,
	float packetsReceivedPerSecond,
	float packetsSentPerSecond,
	ConnectionAttempt lastAttempt
) {
	public boolean latencyKnown() {
		return latencyMillis >= 0;
	}
}

package com.flintclient.client.network.platform;

import com.flintclient.client.network.NetworkPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's live connection numbers. Only getters on objects Minecraft already has: no packet is
 * sent (unlike the F3 network chart, which pings the server every tick while it's open) and nothing is
 * allocated. Main thread.
 */
public final class Mc263NetworkPlatform implements NetworkPlatform {
	@Override
	public int latencyMillis(Object connection) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientPacketListener listener = playing(connection);
		if (listener == null || player == null) {
			return UNKNOWN;
		}
		// The server's own measurement of the keep-alive round trip; negative means it hasn't sent one.
		PlayerInfo info = listener.getPlayerInfo(player.getUUID());
		return info == null || info.getLatency() < 0 ? UNKNOWN : info.getLatency();
	}

	@Override
	public float packetsReceivedPerSecond(Object connection) {
		ClientPacketListener listener = playing(connection);
		return listener == null ? UNKNOWN : listener.getConnection().getAverageReceivedPackets();
	}

	@Override
	public float packetsSentPerSecond(Object connection) {
		ClientPacketListener listener = playing(connection);
		return listener == null ? UNKNOWN : listener.getConnection().getAverageSentPackets();
	}

	// The play listener, only when it's on the tracked connection (never singleplayer's).
	private static @Nullable ClientPacketListener playing(Object connection) {
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		Connection live = listener == null ? null : listener.getConnection();
		return live != null && live == connection ? listener : null;
	}
}

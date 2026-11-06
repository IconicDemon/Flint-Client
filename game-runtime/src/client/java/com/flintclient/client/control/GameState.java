package com.flintclient.client.control;

import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.network.NetworkPlatform;
import net.minecraft.client.Minecraft;

/**
 * The questions about the game the HUD and the Control Center ask, without allocating: in a world, on a server, latency
 * known. (The saved settings and layouts are {@link ControlState}'s.) Render thread.
 */
public final class GameState {
	private GameState() {
	}

	/** A world is loaded and the player is in it. */
	public static boolean inWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && minecraft.player != null;
	}

	/** Playing on a server rather than in singleplayer (an integrated server, even one opened to LAN). */
	public static boolean multiplayer() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getConnection() != null && !minecraft.isLocalServer();
	}

	/** Flint Network Core is running (it can be switched off in flint-performance.json). */
	public static boolean networkCoreRunning() {
		return NetworkCore.active();
	}

	/** The server has reported this player's latency (Flint Network Core reads what Minecraft already has). */
	public static boolean latencyKnown() {
		return NetworkCore.active() && NetworkCore.stats().latencyMillis() != NetworkPlatform.UNKNOWN;
	}
}

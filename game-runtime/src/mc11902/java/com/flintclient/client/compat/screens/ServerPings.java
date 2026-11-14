package com.flintclient.client.compat.screens;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.Util;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;

/**
 * 1.20's finished-ping callback ({@code pingServer(data, onChange, onFinish)}) on Minecraft 1.19.2, whose pinger only says when
 * a server's icon changed. A ping has finished once its round trip time is in: 1.19.2 sets it from the pong, as 1.20 does right
 * before it calls back. {@link #tick} looks for that on the render thread (the pings start on a pool thread) and forgets a ping
 * that never got its pong (it failed, or its pinger was closed) after half a minute.
 */
public final class ServerPings {
	private static final long FORGET_MILLIS = 30_000L;
	private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();

	private ServerPings() {
	}

	private record Pending(ServerData data, Runnable onFinish, long since) {
	}

	/** Pings {@code data}'s server with {@code pinger}; {@code onFinish} runs (render thread) once the ping is measured. */
	public static void ping(ServerStatusPinger pinger, ServerData data, Runnable onFinish) throws UnknownHostException {
		// Unknown until the pong (a server that doesn't resolve never sets it, and a new ServerData's starts at 0).
		data.ping = -1L;
		pinger.pingServer(data, () -> {
		});
		PENDING.add(new Pending(data, onFinish, Util.getMillis()));
	}

	/** Reports the pings that finished since the last tick. Call every client tick while pinging. */
	public static void tick() {
		long now = Util.getMillis();
		List<Runnable> finished = new ArrayList<>();
		PENDING.removeIf(pending -> {
			if (pending.data().ping >= 0) {
				finished.add(pending.onFinish());
				return true;
			}
			return now - pending.since() > FORGET_MILLIS;
		});
		finished.forEach(Runnable::run);
	}
}

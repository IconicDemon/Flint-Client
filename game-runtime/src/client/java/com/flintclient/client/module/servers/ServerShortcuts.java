package com.flintclient.client.module.servers;

import com.flintclient.client.config.FlintConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Quick Play on the main menu's left rail: the servers the player pinned (see {@link QuickPlay}), read from Flint's
 * settings and kept in step with Minecraft's own server list (a renamed server shows its new name; a deleted one is
 * unpinned). Nothing is pinned for a new player, and nothing is ever pinned automatically.
 *
 * <p>Status is checked with Minecraft's own server ping for the pinned servers only, only while the main menu is open
 * and at most once a minute per server. Pings run in parallel on a small pool whose threads exit when idle; a ping that
 * started but got no answer within {@link StatusChecks#TIMEOUT_MILLIS} counts as offline. "Online" is shown only after a
 * successful ping in the last few minutes; anything else is "unknown", never assumed. Each stay on the menu is one
 * {@link PingRound} with its own pinger: closing the menu cancels the pings still queued, closes their connections and
 * discards their results.
 */
public final class ServerShortcuts {
	/** Pool threads exit after this long without a ping. */
	private static final long IDLE_MILLIS = 10_000;

	public enum Status {
		UNKNOWN,
		CHECKING,
		ONLINE,
		INCOMPATIBLE,
		OFFLINE
	}

	private static final StatusChecks CHECKS = new StatusChecks(PingPool.create(QuickPlay.MAX, IDLE_MILLIS),
		task -> Minecraft.getInstance().execute(task), Util::getMillis);
	private static @Nullable PingRound round;
	/** The current round's pinger; each round has its own, so stopping one never touches the next. */
	private static @Nullable ServerStatusPinger pinger;

	private ServerShortcuts() {
	}

	/** The pinned servers, checked against the saved server list (read from servers.dat) and saved if that changed them. */
	public static List<QuickPlay.Entry> pins() {
		return pins(savedServers());
	}

	/** {@link #pins()} against {@code servers}, already read. */
	public static List<QuickPlay.Entry> pins(ServerList servers) {
		FlintConfig config = FlintConfig.get();
		List<QuickPlay.Entry> pinned = config.quickPlay();
		if (pinned.isEmpty()) {
			return pinned;
		}
		List<QuickPlay.Entry> now = QuickPlay.reconcile(pinned, entries(servers));
		if (now != pinned) {
			config.setQuickPlay(now);
		}
		return now;
	}

	private static List<QuickPlay.Entry> entries(ServerList servers) {
		List<QuickPlay.Entry> out = new ArrayList<>(servers.size());
		for (int i = 0; i < servers.size(); i++) {
			ServerData server = servers.get(i);
			out.add(new QuickPlay.Entry(server.name, server.ip));
		}
		return out;
	}

	public static ServerList savedServers() {
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		return list;
	}

	/** Whether the server at {@code address} is pinned. */
	public static boolean pinned(String address) {
		return QuickPlay.contains(FlintConfig.get().quickPlay(), address);
	}

	/** Pins a saved server (saved when it was added); the result says what happened. */
	public static QuickPlay.Result pin(String name, String address) {
		QuickPlay.Change change = QuickPlay.add(FlintConfig.get().quickPlay(), name, address);
		if (change.result() == QuickPlay.Result.ADDED) {
			FlintConfig.get().setQuickPlay(change.list());
		}
		return change.result();
	}

	/** Unpins the server at {@code address}; false when it wasn't pinned. */
	public static boolean unpin(String address) {
		List<QuickPlay.Entry> before = FlintConfig.get().quickPlay();
		List<QuickPlay.Entry> after = QuickPlay.remove(before, address);
		if (after == before) {
			return false;
		}
		FlintConfig.get().setQuickPlay(after);
		return true;
	}

	/** A saved server was edited in Multiplayer: its pin (if any) follows the new name and address. */
	public static void edited(String oldAddress, String name, String newAddress) {
		List<QuickPlay.Entry> before = FlintConfig.get().quickPlay();
		List<QuickPlay.Entry> after = QuickPlay.edited(before, oldAddress, name, newAddress);
		if (after != before) {
			FlintConfig.get().setQuickPlay(after);
		}
	}

	public static Status status(QuickPlay.Entry pin) {
		return CHECKS.status(pin.address());
	}

	public static long pingMillis(QuickPlay.Entry pin) {
		return CHECKS.pingMillis(pin.address());
	}

	public static @Nullable String reportedVersion(QuickPlay.Entry pin) {
		return CHECKS.version(pin.address());
	}

	/** Pings the pinned servers whose last check is older than a minute. Called when the menu opens. */
	public static void refresh(List<QuickPlay.Entry> slots) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.allowsMultiplayer()) {
			return;
		}
		for (QuickPlay.Entry slot : slots) {
			if (!CHECKS.due(slot.address())) {
				continue;
			}
			PingRound activeRound = round();
			ServerStatusPinger activePinger = pinger;
			ServerData data = new ServerData(slot.name(), slot.address(), ServerData.Type.OTHER);
			CHECKS.ping(slot.address(), activeRound, onReply -> {
				activePinger.pingServer(data, () -> {
				}, () -> onReply.accept(result(data)), EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
				return !gaveUp(data);
			});
		}
	}

	private static StatusChecks.Result result(ServerData data) {
		Status status = data.protocol == SharedConstants.getCurrentVersion().protocolVersion() ? Status.ONLINE : Status.INCOMPATIBLE;
		return new StatusChecks.Result(status, data.ping, data.version == null ? null : data.version.getString());
	}

	/**
	 * Vanilla's pinger doesn't throw when the address doesn't resolve (or is blocked): it returns after its
	 * onPingFailed, which sets {@code status} to {@link CommonComponents#EMPTY}. A status reply never sets
	 * that instance and the field starts null, so seeing it means the ping failed (rarely, an opened
	 * connection already closed without a reply, which is a failure too).
	 */
	private static boolean gaveUp(ServerData data) {
		return data.status == CommonComponents.EMPTY;
	}

	private static PingRound round() {
		if (round == null) {
			ServerStatusPinger roundPinger = new ServerStatusPinger();
			pinger = roundPinger;
			round = new PingRound(roundPinger::removeAll);
		}
		return round;
	}

	/** Called every client tick while the menu is open: lets the pinger time out or close connections. */
	public static void tick() {
		if (pinger != null) {
			pinger.tick();
			// A started ping that never answered (the pinger closed it, or it is still connecting) counts as unreachable.
			CHECKS.expire();
		}
	}

	/**
	 * Called whenever the menu is closed or covered (including when a world starts loading): cancels the
	 * pings still queued, closes open status connections and drops results still on the way. Results
	 * already received are kept.
	 */
	public static void stop() {
		if (round != null) {
			round.stop();
			round = null;
			pinger = null;
		}
		CHECKS.dropUnanswered();
	}
}

package com.flintclient.client.module.servers;

import com.flintclient.client.config.FlintConfig;
import com.flintclient.client.mc.Minecraft;
import com.flintclient.client.mc.ServerData;
import com.flintclient.client.mc.ServerList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * Quick Play's pinned servers on Minecraft 1.8.9: the pins (Flint's config, the same file and rules as on every line) against
 * the player's saved servers (servers.dat), and each pinned server's status, pinged as 1.8.9's own server list pings (its
 * pinger, connecting on a worker, answers read each tick) while the main menu shows them, every 10 seconds at most. The 26.x
 * ServerShortcuts pings through newer Minecraft's pinger. Render thread, but the pings' connections.
 */
public final class ServerShortcuts {
	private static final long REPING_MILLIS = 10_000;
	/** A ping that hasn't answered in this long counts as offline. */
	private static final long TIMEOUT_MILLIS = 8_000;

	public enum Status {
		UNKNOWN,
		CHECKING,
		ONLINE,
		INCOMPATIBLE,
		OFFLINE
	}

	/** One pinned server's last check. */
	private static final class Check {
		net.minecraft.client.options.@Nullable ServerListEntry entry;
		Status status = Status.UNKNOWN;
		long ping;
		@Nullable String version;
		long startedAt;
		long answeredAt = -REPING_MILLIS;
		/** The worker couldn't start the ping (an unknown host). */
		volatile boolean failed;
	}

	private static final Map<String, Check> CHECKS = new HashMap<>();
	private static @Nullable ExecutorService workers;
	private static net.minecraft.client.network.@Nullable MultiplayerServerListPinger pinger;

	private ServerShortcuts() {
	}

	public static List<QuickPlay.Entry> pins() {
		return pins(savedServers());
	}

	/** The pins, kept in step with the saved servers (a renamed server shows its new name; a deleted one goes). */
	public static List<QuickPlay.Entry> pins(ServerList servers) {
		FlintConfig config = FlintConfig.get();
		List<QuickPlay.Entry> pinned = config.quickPlay();
		if (pinned.isEmpty()) {
			return pinned;
		}
		List<QuickPlay.Entry> saved = new ArrayList<>(servers.size());
		for (int i = 0; i < servers.size(); i++) {
			ServerData server = servers.get(i);
			saved.add(new QuickPlay.Entry(server.name, server.ip));
		}
		List<QuickPlay.Entry> now = QuickPlay.reconcile(pinned, saved);
		if (now != pinned) {
			config.setQuickPlay(now);
		}
		return now;
	}

	public static ServerList savedServers() {
		ServerList list = new ServerList(Minecraft.getInstance());
		list.load();
		return list;
	}

	public static boolean pinned(String address) {
		return QuickPlay.contains(FlintConfig.get().quickPlay(), address);
	}

	public static QuickPlay.Result pin(String name, String address) {
		QuickPlay.Change change = QuickPlay.add(FlintConfig.get().quickPlay(), name, address);
		if (change.result() == QuickPlay.Result.ADDED) {
			FlintConfig.get().setQuickPlay(change.list());
		}
		return change.result();
	}

	public static boolean unpin(String address) {
		List<QuickPlay.Entry> before = FlintConfig.get().quickPlay();
		List<QuickPlay.Entry> after = QuickPlay.remove(before, address);
		if (after == before) {
			return false;
		}
		FlintConfig.get().setQuickPlay(after);
		return true;
	}

	public static void edited(String oldAddress, String name, String newAddress) {
		List<QuickPlay.Entry> before = FlintConfig.get().quickPlay();
		List<QuickPlay.Entry> after = QuickPlay.edited(before, oldAddress, name, newAddress);
		if (after != before) {
			FlintConfig.get().setQuickPlay(after);
		}
	}

	public static Status status(QuickPlay.Entry pin) {
		Check check = CHECKS.get(QuickPlay.key(pin.address()));
		return check == null ? Status.UNKNOWN : check.status;
	}

	public static long pingMillis(QuickPlay.Entry pin) {
		Check check = CHECKS.get(QuickPlay.key(pin.address()));
		return check == null ? 0 : check.ping;
	}

	public static @Nullable String reportedVersion(QuickPlay.Entry pin) {
		Check check = CHECKS.get(QuickPlay.key(pin.address()));
		return check == null ? null : check.version;
	}

	/** Pings the shown pins that are due (each at most every 10 seconds). */
	public static void refresh(List<QuickPlay.Entry> slots) {
		long now = System.currentTimeMillis();
		for (QuickPlay.Entry slot : slots) {
			Check check = CHECKS.computeIfAbsent(QuickPlay.key(slot.address()), k -> new Check());
			if (check.entry != null || now - check.answeredAt < REPING_MILLIS) {
				continue;
			}
			net.minecraft.client.options.ServerListEntry entry = new net.minecraft.client.options.ServerListEntry(slot.name(), slot.address(), false);
			check.entry = entry;
			check.startedAt = now;
			check.failed = false;
			if (check.status == Status.UNKNOWN) {
				check.status = Status.CHECKING;
			}
			net.minecraft.client.network.MultiplayerServerListPinger p = pinger();
			workers().execute(() -> {
				try {
					p.add(entry);
				} catch (Exception e) {
					check.failed = true;
				}
			});
		}
	}

	private static net.minecraft.client.network.MultiplayerServerListPinger pinger() {
		if (pinger == null) {
			pinger = new net.minecraft.client.network.MultiplayerServerListPinger();
		}
		return pinger;
	}

	private static ExecutorService workers() {
		if (workers == null) {
			workers = Executors.newFixedThreadPool(2, task -> {
				Thread thread = new Thread(task, "Flint Quick Play ping");
				thread.setDaemon(true);
				return thread;
			});
		}
		return workers;
	}

	/** Each tick while the main menu shows: the answers in so far (1.8.9's pinger fills the entry as its server answers). */
	public static void tick() {
		if (pinger != null) {
			pinger.tick();
		}
		long now = System.currentTimeMillis();
		for (Check check : CHECKS.values()) {
			net.minecraft.client.options.ServerListEntry entry = check.entry;
			if (entry == null) {
				continue;
			}
			if (check.failed || now - check.startedAt > TIMEOUT_MILLIS) {
				finish(check, Status.OFFLINE, now);
			} else if (entry.version != null && entry.ping > 0) {
				check.ping = entry.ping;
				check.version = entry.version;
				finish(check, entry.protocol == 47 ? Status.ONLINE : Status.INCOMPATIBLE, now);
			}
		}
	}

	private static void finish(Check check, Status status, long now) {
		check.status = status;
		check.entry = null;
		check.answeredAt = now;
	}

	/** The main menu went: pings still connecting are dropped. */
	public static void stop() {
		if (pinger != null) {
			pinger.cancel();
		}
		for (Check check : CHECKS.values()) {
			if (check.entry != null) {
				check.entry = null;
				if (check.status == Status.CHECKING) {
					check.status = Status.UNKNOWN;
				}
			}
		}
	}
}

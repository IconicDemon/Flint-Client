package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.mods.CameraMods;
import com.flintclient.client.control.model.mods.LookMods;
import com.flintclient.client.control.model.mods.MapMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Fair play by server: the Flint mods a server's own rules don't allow (off there, as Lunar Client turns Freelook off on
 * Hypixel), and the limits Flint keeps on every multiplayer server (the maps show no entity radar and no cave layers, as Xaero's
 * Fair-Play minimap). The version layer reports where you are each tick ({@link #update}); {@link Mods#on} and the HUD read
 * the result. Pure Java, allocation-free except when a note is made.
 *
 * <p>Hypixel's Allowed Modifications (support.hypixel.net, article 6472550754962) name mini-maps, other players' health and
 * armor indicators, player distance, and changes of perspective that let you see around or over objects as not allowed:
 * Freelook, the Camera module's offsets and distance, the Minimap and the World Map, Health Indicators and Team Tracker are off
 * there, and Damage Indicator shows mobs only. Hoplite locks Freelook and the Minimap (Lunar Client does it there too).
 */
public final class ServerRules {
	/**
	 * One server's rules: the host it is reached at (and every subdomain), a word its brand says (servers reached through
	 * another address still say who they are), and what is off there.
	 */
	record Rule(String host, String name, @Nullable String brand, List<String> off, boolean playerHealth) {
	}

	static final List<Rule> RULES = List.of(
		new Rule("hypixel.net", "Hypixel", "hypixel", List.of(PlayerMods.Freelook.ID, CameraMods.Camera.ID, MapMods.Minimap.ID, MapMods.WorldMap.ID,
			PlayerMods.HealthIndicators.ID, LookMods.TeamTracker.ID), false),
		new Rule("hoplite.gg", "Hoplite", null, List.of(PlayerMods.Freelook.ID, MapMods.Minimap.ID, MapMods.WorldMap.ID), true));

	private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
	private static final boolean[] BLOCKED = new boolean[ModuleRegistry.size()];
	private static final boolean[] NAMED = new boolean[ModuleRegistry.size()];

	private static @Nullable Rule rule;
	private static boolean server;
	private static int revision;
	private static boolean lastInWorld;
	private static boolean lastLocal;
	private static @Nullable String lastAddress;
	private static @Nullable String lastBrand;

	private ServerRules() {
	}

	/**
	 * Where you are now: in a world or not, whether it is this computer's own (singleplayer, or a world you opened to LAN), the
	 * address of the server and the brand it reports. Returns whether the rules changed. Cheap when nothing changed.
	 */
	public static synchronized boolean update(boolean inWorld, boolean local, @Nullable String address, @Nullable String brand) {
		if (inWorld == lastInWorld && local == lastLocal && Objects.equals(address, lastAddress) && Objects.equals(brand, lastBrand)) {
			return false;
		}
		lastInWorld = inWorld;
		lastLocal = local;
		lastAddress = address;
		lastBrand = brand;
		boolean onServer = inWorld && !local;
		Rule next = onServer ? match(address, brand) : null;
		if (next == rule && onServer == server) {
			return false;
		}
		rule = next;
		server = onServer;
		java.util.Arrays.fill(BLOCKED, false);
		java.util.Arrays.fill(NAMED, false);
		if (next != null) {
			for (String id : next.off()) {
				int index = ModuleRegistry.indexOf(id);
				if (index >= 0) {
					BLOCKED[index] = true;
				}
			}
		}
		revision++;
		return true;
	}

	/** Back to no world (tests, and leaving a world). */
	public static void reset() {
		update(false, false, null, null);
	}

	/** The rules of the server at {@code address} that reports {@code brand}, or null when it has none of its own. */
	static @Nullable Rule match(@Nullable String address, @Nullable String brand) {
		String host = host(address);
		String said = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
		for (Rule r : RULES) {
			if (host != null && (host.equals(r.host()) || host.endsWith("." + r.host()))) {
				return r;
			}
			if (r.brand() != null && said.contains(r.brand())) {
				return r;
			}
		}
		return null;
	}

	/** The host in a server address ({@code mc.hypixel.net:25565} → {@code mc.hypixel.net}), or null when it isn't a host name. */
	static @Nullable String host(@Nullable String address) {
		if (address == null) {
			return null;
		}
		String host = address.strip().toLowerCase(Locale.ROOT);
		int port = host.lastIndexOf(':');
		if (port >= 0 && host.indexOf(':') == port) {
			host = host.substring(0, port);
		}
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		return HOST.matcher(host).matches() ? host : null;
	}

	/**
	 * Whether module {@code index} is off here, whatever it is set to: this server's rules keep it off, or it failed this
	 * session and Flint switched it off ({@link Faults}).
	 */
	public static boolean blocked(int index) {
		return index >= 0 && index < BLOCKED.length && (BLOCKED[index] || Faults.off(index));
	}

	/**
	 * Whether you play on a multiplayer server (not your own world): the maps keep to fair play there, no entity radar and no
	 * cave layers, which would show what is behind walls and under the ground.
	 */
	public static boolean fairPlay() {
		return server;
	}

	/** Whether other players' health and armor may show (Damage Indicator): not where a server's rules say no. */
	public static boolean playerHealthShown() {
		Rule r = rule;
		return r == null || r.playerHealth();
	}

	/** The name of the server whose own rules apply now ("Hypixel"), or null. */
	public static @Nullable String serverName() {
		Rule r = rule;
		return r == null ? null : r.name();
	}

	/** Changes each time the rules do (to redo what was worked out from them). */
	public static int revision() {
		return revision + Faults.revision();
	}

	/** Why module {@code id} does nothing here ("Off on Hypixel: its rules don't allow it"), or null. */
	public static @Nullable String reason(String id) {
		int index = ModuleRegistry.indexOf(id);
		if (Faults.off(index)) {
			return "Off after an error: Flint turned it off for this session";
		}
		Rule r = rule;
		return r != null && blocked(index) ? "Off on " + r.name() + ": its rules don't allow it" : null;
	}

	/**
	 * The note to show now, or null: the modules turned on ({@code enabled}) that this server's rules keep off, each named once
	 * while it stays on (turned off and on again, it is named again).
	 */
	public static synchronized @Nullable String notice(IntPredicate enabled) {
		Rule r = rule;
		if (r == null) {
			return null;
		}
		List<String> names = null;
		for (int i = 0; i < BLOCKED.length; i++) {
			if (!BLOCKED[i]) {
				continue;
			}
			if (!enabled.test(i)) {
				NAMED[i] = false;
			} else if (!NAMED[i]) {
				NAMED[i] = true;
				if (names == null) {
					names = new ArrayList<>();
				}
				names.add(ModuleRegistry.all().get(i).name());
			}
		}
		return names == null ? null : sentence(names, r.name());
	}

	/** "Freelook is off on Hypixel: its rules don't allow it", "Freelook and Minimap are off…", "A, B and C are off…". */
	static String sentence(List<String> names, String server) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) {
				b.append(i == names.size() - 1 ? " and " : ", ");
			}
			b.append(names.get(i));
		}
		boolean one = names.size() == 1;
		return b.append(one ? " is off on " : " are off on ").append(server).append(one ? ": its rules don't allow it"
			: ": its rules don't allow them").toString();
	}
}

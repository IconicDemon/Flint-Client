package com.flintclient.legacy.waypoints;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.platform.World;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;

/**
 * The 1.8.9 Waypoints mod's view of Flint's shared waypoints ({@code control.feature.WaypointStore}, one file per world or server
 * in {@code config/flintclient/waypoints}): the same waypoints the shared Right Shift menu lists and edits, and the ones 26.x and
 * 1.21.x show for the same world or server. The 1.8.9 renderer and keys work on {@link Waypoint}s (1.8.9 numbers dimensions);
 * each change is written through to the shared store at once, and a change made elsewhere (the menu) is picked up on the next
 * read. A world's old 1.8.9 file ({@code flint/waypoints-1.8.9/<world>.json}) is carried over the first time that world is
 * opened, then kept as {@code .migrated}. Render thread.
 */
public final class WaypointStore {
	public static final int MAX = 1000;
	public static final int[] PALETTE = {0xFF3020, 0xFFA01C, 0xFFE530, 0x21D85B, 0x19B7FF, 0x5D39FF, 0xF045B7};
	public static final String LATEST_DEATH = "☠ Latest Death";
	public static final String OLD_DEATH = "☠ Old Death";
	public static final int DEATH_COLOR = 0xC8C8C8;

	private final File folder;
	private final com.flintclient.client.control.feature.WaypointStore shared;
	/** The shared waypoints as 1.8.9 waypoints, index for index; remade when the shared list changed elsewhere. */
	private final List<Waypoint> list = new ArrayList<Waypoint>();
	private Waypoint[] array = new Waypoint[0];
	private String world;
	private int seen = -1;

	/** The waypoints in Flint's shared store ({@code Features.waypoints()}). */
	public WaypointStore(File gameDir) {
		this(gameDir, Features.waypoints());
	}

	public WaypointStore(File gameDir, com.flintclient.client.control.feature.WaypointStore shared) {
		this.folder = new File(gameDir, "flint/waypoints-1.8.9");
		this.shared = shared;
	}

	/** The old 1.8.9 file name for a world key. */
	static String fileName(String world) {
		StringBuilder out = new StringBuilder();
		for (char c : world.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' ? c : '_');
		}
		if (out.length() > 80) {
			out.setLength(80);
		}
		return out + ".json";
	}

	/** A 1.8.9 world key ({@code "sp-<save folder>"}, {@code "mp-<address>"}) as every line names it ({@code "sp:"}, {@code "mp:"}). */
	static String sharedKey(String key) {
		if (key.startsWith("sp-")) {
			return "sp:" + key.substring(3);
		}
		if (key.startsWith("mp-")) {
			return "mp:" + key.substring(3).toLowerCase(Locale.ROOT).trim();
		}
		return key;
	}

	/** The world or server whose waypoints are open (null: none). */
	public String world() {
		return world;
	}

	/** Opens {@code key}'s waypoints ({@code "sp-<save folder>"}, {@code "mp-<address>"}); null closes them. */
	public void open(String key) {
		if (key == null ? world == null : key.equals(world)) {
			return;
		}
		world = key;
		shared.enter(key == null ? null : sharedKey(key));
		if (key != null) {
			carryOver(new File(folder, fileName(key)));
		}
		seen = -1;
		sync();
	}

	/** Brings an old 1.8.9 file's waypoints into the shared store, once. */
	private void carryOver(File old) {
		if (!old.isFile()) {
			return;
		}
		int carried = 0;
		try (Reader in = Files.newBufferedReader(old.toPath(), StandardCharsets.UTF_8)) {
			JsonElement parsed = new JsonParser().parse(in);
			JsonArray items = parsed.isJsonObject() && parsed.getAsJsonObject().has("waypoints")
				? parsed.getAsJsonObject().getAsJsonArray("waypoints") : new JsonArray();
			for (JsonElement e : items) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject o = e.getAsJsonObject();
				Waypoint w = new Waypoint(string(o, "name"), number(o, "x"), number(o, "y"), number(o, "z"), number(o, "dimension"),
					parseColor(string(o, "color")));
				w.visible = !o.has("visible") || o.get("visible").getAsBoolean();
				w.death = o.has("death") && o.get("death").getAsBoolean();
				if (shared.add(toShared(w)) >= 0) {
					carried++;
				}
			}
			Files.move(old.toPath(), new File(old.getPath() + ".migrated").toPath(), StandardCopyOption.REPLACE_EXISTING);
			LogManager.getLogger("Flint").info("Flint carried {} waypoints of {} over to the shared waypoints", carried, old.getName());
		} catch (Exception e) {
			LogManager.getLogger("Flint").warn("Flint couldn't carry the 1.8.9 waypoints in " + old + " over; the file is left as it was", e);
		}
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	private static int number(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : 0;
	}

	static int parseColor(String hex) {
		try {
			return (int) Long.parseLong(hex.replace("#", ""), 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return PALETTE[0];
		}
	}

	// ---------- the two forms ----------

	private com.flintclient.client.control.feature.Waypoint toShared(Waypoint w) {
		String dimension = World.dimensionId(w.dimension);
		com.flintclient.client.control.feature.Waypoint s = new com.flintclient.client.control.feature.Waypoint(w.name, w.x, w.y, w.z, dimension,
			w.rgb, w.visible);
		return w.death ? s.withKind(com.flintclient.client.control.feature.Waypoint.DEATH) : s.withGroup(shared.activeSet(dimension));
	}

	private static Waypoint from(com.flintclient.client.control.feature.Waypoint s) {
		Waypoint w = new Waypoint(s.name(), s.x(), s.y(), s.z(), World.dimensionNumber(s.dimension()), s.rgb());
		w.visible = s.visible();
		w.death = s.death();
		return w;
	}

	/** Remakes the 1.8.9 list when the shared one changed elsewhere. */
	private void sync() {
		if (shared.revision() == seen) {
			return;
		}
		list.clear();
		for (com.flintclient.client.control.feature.Waypoint s : shared.all()) {
			list.add(from(s));
		}
		seen = shared.revision();
		array = list.toArray(new Waypoint[0]);
	}

	/** {@code w} changed here: written through to the shared store. */
	private void write(Waypoint w) {
		int index = list.indexOf(w);
		if (index >= 0) {
			com.flintclient.client.control.feature.Waypoint now = shared.all().get(index);
			com.flintclient.client.control.feature.Waypoint next = now.withName(w.name).withPosition(w.x, w.y, w.z).withColor(w.rgb)
				.withVisible(w.visible).withDimension(World.dimensionId(w.dimension));
			shared.set(index, next);
			seen = shared.revision();
			array = list.toArray(new Waypoint[0]);
		}
	}

	// ---------- the list ----------

	public Waypoint[] array() {
		sync();
		return array;
	}

	public List<Waypoint> all() {
		sync();
		return Collections.unmodifiableList(list);
	}

	public int revision() {
		return shared.revision();
	}

	/** The colour of the next waypoint: the palette in turn, by how many waypoints there are. */
	public int nextColor() {
		sync();
		int made = 0;
		for (Waypoint w : list) {
			if (!w.death) {
				made++;
			}
		}
		return PALETTE[made % PALETTE.length];
	}

	public boolean add(Waypoint w) {
		sync();
		if (world == null || list.size() >= MAX || shared.add(toShared(w)) < 0) {
			return false;
		}
		list.add(w);
		seen = shared.revision();
		array = list.toArray(new Waypoint[0]);
		return true;
	}

	public void remove(Waypoint w) {
		sync();
		int index = list.indexOf(w);
		if (index >= 0) {
			list.remove(index);
			shared.remove(index);
			seen = shared.revision();
			array = list.toArray(new Waypoint[0]);
		}
	}

	public Waypoint at(int x, int y, int z, int dimension) {
		sync();
		for (Waypoint w : list) {
			if (!w.death && w.x == x && w.y == y && w.z == z && w.dimension == dimension) {
				return w;
			}
		}
		return null;
	}

	public void rename(Waypoint w, String name) {
		String clean = Waypoint.clean(name);
		if (!clean.equals(w.name)) {
			w.name = clean;
			write(w);
		}
	}

	public void recolor(Waypoint w, int rgb) {
		if ((rgb & 0xFFFFFF) != w.rgb) {
			w.rgb = rgb & 0xFFFFFF;
			write(w);
		}
	}

	public void setVisible(Waypoint w, boolean visible) {
		if (visible != w.visible) {
			w.visible = visible;
			write(w);
		}
	}

	public void move(Waypoint w, int x, int y, int z, int dimension) {
		w.x = x;
		w.y = y;
		w.z = z;
		w.dimension = dimension;
		write(w);
	}

	/** A death waypoint where you died; the one before becomes an old death, and only the newest {@code keep} deaths are kept. */
	public Waypoint addDeath(int x, int y, int z, int dimension, int keep) {
		sync();
		if (world == null) {
			return null;
		}
		for (Waypoint w : list) {
			if (w.death && LATEST_DEATH.equals(w.name)) {
				w.name = OLD_DEATH;
				write(w);
			}
		}
		Waypoint d = new Waypoint(LATEST_DEATH, x, y, z, dimension, DEATH_COLOR);
		d.death = true;
		if (!add(d)) {
			return null;
		}
		if (shared.trimDeaths(Math.max(1, keep)) > 0) {
			seen = -1;
			sync();
			return list.isEmpty() ? null : list.get(list.size() - 1);
		}
		return d;
	}
}

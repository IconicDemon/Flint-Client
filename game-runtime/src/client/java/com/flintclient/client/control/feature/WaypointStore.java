package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.JsonStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints, one file per world or server ({@code config/flintclient/waypoints/<key>.json}), so a private server's places
 * never show on another. The key comes from the version layer ("sp:" + the save folder, "mp:" + the server address); the
 * file name is a readable part of it plus a short hash, so two keys never share a file. The current world's list is read
 * when the world is joined and written on every change (waypoints change only when you act).
 *
 * <p><b>Sets.</b> A waypoint's group is its set ("" is the default set). Each dimension has an active set; the maps and
 * markers can show only it ({@link #shown}). A set exists while a waypoint is in it, or once made by name ({@link #addSet})
 * until it is removed. The minimap, the world map and the in-world markers all read this one store, so a change shows in all
 * three at once. Version-neutral; render thread.
 */
public final class WaypointStore {
	static final int SCHEMA = 1;
	private static final int MAX = 500;

	private final Path directory;
	private @Nullable String worldKey;
	private @Nullable JsonStore file;
	private final List<Waypoint> waypoints = new ArrayList<>();
	/** Per dimension: the active set, and sets made by name that may hold no waypoint yet. */
	private final Map<String, String> activeSets = new HashMap<>();
	private final Map<String, List<String>> namedSets = new HashMap<>();
	private int revision;

	public WaypointStore(Path directory) {
		this.directory = directory;
	}

	/** The file name for a world key: a cleaned prefix and 10 hex digits of its SHA-256. */
	public static String fileName(String key) {
		return safeName(key) + ".json";
	}

	/** A world key as a safe file or folder name: a cleaned prefix and 10 hex digits of its SHA-256. */
	public static String safeName(String key) {
		String readable = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		if (readable.length() > 40) {
			readable = readable.substring(0, 40);
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return readable + "-" + HexFormat.of().formatHex(hash, 0, 5);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Switches to the world {@code key} (null: no world), reading its file. */
	public void enter(@Nullable String key) {
		if (java.util.Objects.equals(key, worldKey)) {
			return;
		}
		worldKey = key;
		waypoints.clear();
		activeSets.clear();
		namedSets.clear();
		revision++;
		file = key == null ? null : new JsonStore(directory.resolve(fileName(key)), SCHEMA);
		if (file == null) {
			return;
		}
		JsonElement list = file.root().get("waypoints");
		if (list instanceof JsonArray array) {
			for (JsonElement element : array) {
				Waypoint read = read(element);
				if (read != null && waypoints.size() < MAX) {
					waypoints.add(read);
				}
			}
		}
		if (file.root().get("activeSets") instanceof JsonObject active) {
			for (Map.Entry<String, JsonElement> e : active.entrySet()) {
				if (e.getValue().isJsonPrimitive()) {
					activeSets.put(e.getKey(), Waypoint.cleanGroup(e.getValue().getAsString()));
				}
			}
		}
		if (file.root().get("sets") instanceof JsonObject sets) {
			for (Map.Entry<String, JsonElement> e : sets.entrySet()) {
				if (e.getValue() instanceof JsonArray names) {
					List<String> named = new ArrayList<>();
					for (JsonElement n : names) {
						String name = n.isJsonPrimitive() ? Waypoint.cleanGroup(n.getAsString()) : "";
						if (!name.isEmpty() && !named.contains(name) && named.size() < 64) {
							named.add(name);
						}
					}
					namedSets.put(e.getKey(), named);
				}
			}
		}
	}

	static @Nullable Waypoint read(JsonElement element) {
		if (!(element instanceof JsonObject o)) {
			return null;
		}
		try {
			String name = o.has("name") ? o.get("name").getAsString() : "Waypoint";
			int x = o.get("x").getAsInt();
			int y = o.get("y").getAsInt();
			int z = o.get("z").getAsInt();
			String dimension = o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld";
			int rgb = o.has("color") ? parseColor(o.get("color").getAsString()) : 0xFF8139;
			boolean visible = !o.has("visible") || o.get("visible").getAsBoolean();
			String group = o.has("group") ? o.get("group").getAsString() : "";
			int kind = o.has("kind") && "death".equals(o.get("kind").getAsString()) ? Waypoint.DEATH : Waypoint.NORMAL;
			String initials = o.has("initials") ? o.get("initials").getAsString() : "";
			return new Waypoint(name, x, y, z, dimension, rgb, visible, group, flag(o, "showText", true), flag(o, "showDistance", true),
				flag(o, "beam", true), flag(o, "highlight", false), kind, initials);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	private static boolean flag(JsonObject o, String key, boolean missing) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : missing;
	}

	private static int parseColor(String text) {
		int value = com.flintclient.client.control.model.RgbSetting.parseHex(text);
		return value < 0 ? 0xFF8139 : value;
	}

	static JsonObject write(Waypoint w) {
		JsonObject o = new JsonObject();
		o.add("name", new JsonPrimitive(w.name()));
		o.add("x", new JsonPrimitive(w.x()));
		o.add("y", new JsonPrimitive(w.y()));
		o.add("z", new JsonPrimitive(w.z()));
		o.add("dimension", new JsonPrimitive(w.dimension()));
		o.add("color", new JsonPrimitive(com.flintclient.client.control.model.RgbSetting.hex(w.rgb())));
		o.add("visible", new JsonPrimitive(w.visible()));
		if (!w.group().isEmpty()) {
			o.add("group", new JsonPrimitive(w.group()));
		}
		o.add("showText", new JsonPrimitive(w.showText()));
		o.add("showDistance", new JsonPrimitive(w.showDistance()));
		o.add("beam", new JsonPrimitive(w.beam()));
		o.add("highlight", new JsonPrimitive(w.highlight()));
		if (w.death()) {
			o.add("kind", new JsonPrimitive("death"));
		}
		if (!w.initials().isEmpty()) {
			o.add("initials", new JsonPrimitive(w.initials()));
		}
		return o;
	}

	private void save() {
		revision++;
		JsonStore store = file;
		if (store == null) {
			return;
		}
		JsonArray array = new JsonArray();
		for (Waypoint w : waypoints) {
			array.add(write(w));
		}
		store.root().add("waypoints", array);
		JsonObject active = new JsonObject();
		activeSets.forEach((dimension, set) -> {
			if (!set.isEmpty()) {
				active.add(dimension, new JsonPrimitive(set));
			}
		});
		store.root().add("activeSets", active);
		JsonObject sets = new JsonObject();
		namedSets.forEach((dimension, names) -> {
			JsonArray named = new JsonArray();
			names.forEach(named::add);
			sets.add(dimension, named);
		});
		store.root().add("sets", sets);
		store.markDirty();
		store.save();
	}

	public @Nullable String worldKey() {
		return worldKey;
	}

	/** Every waypoint of this world (all dimensions), oldest first. */
	public List<Waypoint> all() {
		return Collections.unmodifiableList(waypoints);
	}

	/** Grows with every change, for views that cache. */
	public int revision() {
		return revision;
	}

	/** Adds one; returns its index, or -1 outside a world or at the limit. */
	public int add(Waypoint waypoint) {
		if (file == null || waypoints.size() >= MAX) {
			return -1;
		}
		waypoints.add(waypoint);
		save();
		return waypoints.size() - 1;
	}

	public void set(int index, Waypoint waypoint) {
		if (index >= 0 && index < waypoints.size() && !waypoints.get(index).equals(waypoint)) {
			waypoints.set(index, waypoint);
			save();
		}
	}

	public void remove(int index) {
		if (index >= 0 && index < waypoints.size()) {
			waypoints.remove(index);
			save();
		}
	}

	/** Where {@code waypoint} is in {@link #all} (that object, else an equal one), or -1. */
	public int indexOf(Waypoint waypoint) {
		for (int i = 0; i < waypoints.size(); i++) {
			if (waypoints.get(i) == waypoint) {
				return i;
			}
		}
		return waypoints.indexOf(waypoint);
	}

	// ---------- sets ----------

	/** The active set of {@code dimension} ("": the default set). */
	public String activeSet(String dimension) {
		return activeSets.getOrDefault(dimension, "");
	}

	/** Makes {@code set} the active set of {@code dimension} (made first if it doesn't exist). */
	public void setActiveSet(String dimension, String set) {
		String clean = Waypoint.cleanGroup(set);
		if (clean.equals(activeSet(dimension))) {
			return;
		}
		if (!sets(dimension).contains(clean)) {
			namedSets.computeIfAbsent(dimension, k -> new ArrayList<>()).add(clean);
		}
		activeSets.put(dimension, clean);
		save();
	}

	/** The sets of {@code dimension}: the default set ("") first, then every other in the order they appeared. */
	public List<String> sets(String dimension) {
		List<String> out = new ArrayList<>();
		out.add("");
		for (String name : namedSets.getOrDefault(dimension, List.of())) {
			if (!out.contains(name)) {
				out.add(name);
			}
		}
		for (Waypoint w : waypoints) {
			if (w.dimension().equals(dimension) && !out.contains(w.group())) {
				out.add(w.group());
			}
		}
		return out;
	}

	/** Makes a set by name in {@code dimension} (nothing for the default set or one that exists). */
	public void addSet(String dimension, String set) {
		String clean = Waypoint.cleanGroup(set);
		if (clean.isEmpty() || sets(dimension).contains(clean)) {
			return;
		}
		namedSets.computeIfAbsent(dimension, k -> new ArrayList<>()).add(clean);
		save();
	}

	/** Removes a set of {@code dimension} and every waypoint in it (the default set is only emptied). */
	public void removeSet(String dimension, String set) {
		boolean changed = waypoints.removeIf(w -> w.dimension().equals(dimension) && w.group().equals(set) && !w.death());
		List<String> named = namedSets.get(dimension);
		changed |= named != null && named.remove(set);
		if (activeSet(dimension).equals(set) && activeSets.remove(dimension) != null) {
			changed = true;
		}
		if (changed) {
			save();
		}
	}

	/** Renames a set of {@code dimension}, and its waypoints with it. */
	public void renameSet(String dimension, String from, String to) {
		String clean = Waypoint.cleanGroup(to);
		if (from.isEmpty() || clean.isEmpty() || clean.equals(from) || sets(dimension).contains(clean)) {
			return;
		}
		for (int i = 0; i < waypoints.size(); i++) {
			Waypoint w = waypoints.get(i);
			if (w.dimension().equals(dimension) && w.group().equals(from)) {
				waypoints.set(i, w.withGroup(clean));
			}
		}
		List<String> named = namedSets.get(dimension);
		if (named != null) {
			int at = named.indexOf(from);
			if (at >= 0) {
				named.set(at, clean);
			}
		}
		if (activeSet(dimension).equals(from)) {
			activeSets.put(dimension, clean);
		}
		save();
	}

	/**
	 * Whether a waypoint shows on the maps and as a marker: it is visible and, unless {@code allSets}, in its dimension's
	 * active set. Death waypoints show whatever the set.
	 */
	public boolean shown(Waypoint w, boolean allSets) {
		return w.visible() && (allSets || w.death() || w.group().equals(activeSet(w.dimension())));
	}

	/** Keeps only the newest {@code keep} death waypoints (the newest is last in the list); 0 keeps them all. */
	public int trimDeaths(int keep) {
		if (keep <= 0) {
			return 0;
		}
		int deaths = 0;
		for (Waypoint w : waypoints) {
			deaths += w.death() ? 1 : 0;
		}
		int remove = deaths - keep;
		if (remove <= 0) {
			return 0;
		}
		int removed = 0;
		for (int i = 0; i < waypoints.size() && removed < remove; ) {
			if (waypoints.get(i).death()) {
				waypoints.remove(i);
				removed++;
			} else {
				i++;
			}
		}
		save();
		return removed;
	}

	/** "Waypoint 3": the first number not used by a waypoint called that. */
	public String nextName(String base) {
		for (int n = 1; ; n++) {
			String candidate = base + " " + n;
			boolean taken = false;
			for (Waypoint w : waypoints) {
				taken |= w.name().equalsIgnoreCase(candidate);
			}
			if (!taken) {
				return candidate;
			}
		}
	}
}

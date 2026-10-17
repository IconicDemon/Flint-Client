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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints, one file per world or server ({@code config/flintclient/waypoints/<key>.json}), so a private server's places
 * never show on another. The key comes from the version layer ("sp:" + the save folder, "mp:" + the server address); the
 * file name is a readable part of it plus a short hash, so two keys never share a file. The current world's list is read
 * when the world is joined and written on every change (waypoints change only when you act). Version-neutral; render
 * thread.
 */
public final class WaypointStore {
	static final int SCHEMA = 1;
	private static final int MAX = 500;

	private final Path directory;
	private @Nullable String worldKey;
	private @Nullable JsonStore file;
	private final List<Waypoint> waypoints = new ArrayList<>();
	private int revision;

	public WaypointStore(Path directory) {
		this.directory = directory;
	}

	/** The file name for a world key: a cleaned prefix and 10 hex digits of its SHA-256. */
	static String fileName(String key) {
		String readable = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
		if (readable.length() > 40) {
			readable = readable.substring(0, 40);
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return readable + "-" + HexFormat.of().formatHex(hash, 0, 5) + ".json";
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
			return new Waypoint(name, x, y, z, dimension, rgb, visible, group, flag(o, "showText", true), flag(o, "showDistance", true),
				flag(o, "beam", true), flag(o, "highlight", false));
		} catch (RuntimeException e) {
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

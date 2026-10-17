package com.flintclient.client.control.feature;

import com.flintclient.client.control.store.JsonStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pack Organizer's folders: named groups the player sorts resource packs into, stored in Flint's own file
 * ({@code config/flintclient/pack-folders.json}). They are labels only: a pack's file is never moved, renamed or deleted,
 * and putting a pack in a folder never changes which packs are on or their order. A pack not in any folder is "Unsorted".
 * Packs that disappear keep their folder in the file (a pack moved out and back finds its place again). Version-neutral;
 * render thread; saved on every change (changes are clicks, never per frame).
 */
public final class PackFolders {
	static final int SCHEMA = 1;
	public static final int MAX_FOLDERS = 64;
	public static final int MAX_NAME = 32;

	private final @Nullable JsonStore file;
	private final List<String> folders = new ArrayList<>();
	private final Map<String, String> packs = new LinkedHashMap<>();
	private int revision;

	/** Folders read from {@code file} (null: kept in memory only, for tests). */
	public PackFolders(@Nullable Path file) {
		this.file = file == null ? null : new JsonStore(file, SCHEMA);
		if (this.file != null) {
			read(this.file.root());
		}
	}

	private void read(JsonObject root) {
		JsonElement list = root.get("folders");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
					String name = clean(e.getAsString());
					if (!name.isEmpty() && indexOf(name) < 0 && folders.size() < MAX_FOLDERS) {
						folders.add(name);
					}
				}
			}
		}
		JsonElement map = root.get("packs");
		if (map != null && map.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : map.getAsJsonObject().entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
					int i = indexOf(e.getValue().getAsString());
					if (i >= 0) {
						packs.put(e.getKey(), folders.get(i));
					}
				}
			}
		}
	}

	/** A name as stored: no control characters, trimmed, at most {@value #MAX_NAME} characters. */
	static String clean(String name) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
			char ch = name.charAt(i);
			if (!Character.isISOControl(ch) && ch != '§') {
				out.append(ch);
			}
		}
		return out.toString().strip();
	}

	/** Changes each time anything changes (views rebuild on it). */
	public int revision() {
		return revision;
	}

	/** The folders, in the order they were made. */
	public List<String> folders() {
		return Collections.unmodifiableList(folders);
	}

	/** {@code packId}'s folder, or null (Unsorted). */
	public @Nullable String folderOf(String packId) {
		return packs.get(packId);
	}

	private int indexOf(String name) {
		for (int i = 0; i < folders.size(); i++) {
			if (folders.get(i).equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	/** Makes a folder called {@code base} (or "base 2", "base 3"... if taken); returns its name, or null at the limit. */
	public @Nullable String create(String base) {
		if (folders.size() >= MAX_FOLDERS) {
			return null;
		}
		String clean = clean(base);
		if (clean.isEmpty()) {
			clean = "Folder";
		}
		String name = clean;
		for (int n = 2; indexOf(name) >= 0; n++) {
			String suffix = " " + n;
			name = clean.substring(0, Math.min(clean.length(), MAX_NAME - suffix.length())) + suffix;
		}
		folders.add(name);
		changed();
		return name;
	}

	/** Renames folder {@code from}; false if the new name is empty or another folder has it. */
	public boolean rename(String from, String to) {
		int i = indexOf(from);
		String name = clean(to);
		if (i < 0 || name.isEmpty() || indexOf(name) >= 0 && indexOf(name) != i) {
			return false;
		}
		String old = folders.get(i);
		folders.set(i, name);
		packs.replaceAll((pack, folder) -> folder.equals(old) ? name : folder);
		changed();
		return true;
	}

	/** Removes a folder; its packs go back to Unsorted (the packs themselves are untouched). */
	public boolean remove(String name) {
		int i = indexOf(name);
		if (i < 0) {
			return false;
		}
		String old = folders.remove(i);
		packs.values().removeIf(folder -> folder.equals(old));
		changed();
		return true;
	}

	/** Puts {@code packId} in {@code folder} (null: Unsorted). */
	public boolean move(String packId, @Nullable String folder) {
		if (folder == null) {
			if (packs.remove(packId) == null) {
				return false;
			}
		} else {
			int i = indexOf(folder);
			if (i < 0 || folders.get(i).equals(packs.get(packId))) {
				return false;
			}
			packs.put(packId, folders.get(i));
		}
		changed();
		return true;
	}

	/** The folder after {@code current} in order, then Unsorted (null), then the first again: for a "move to" stepper. */
	public @Nullable String next(@Nullable String current) {
		if (folders.isEmpty()) {
			return null;
		}
		if (current == null) {
			return folders.get(0);
		}
		int i = indexOf(current);
		return i < 0 || i == folders.size() - 1 ? null : folders.get(i + 1);
	}

	/** Whether a pack's name or description matches a search (every word, any case). */
	public static boolean matches(String query, String title, String description) {
		String text = (title + " " + description).toLowerCase(Locale.ROOT);
		for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
			if (!word.isEmpty() && !text.contains(word)) {
				return false;
			}
		}
		return true;
	}

	private void changed() {
		revision++;
		JsonStore store = file;
		if (store == null || store.readOnly()) {
			return;
		}
		JsonObject root = store.root();
		JsonArray list = new JsonArray();
		folders.forEach(list::add);
		root.add("folders", list);
		JsonObject map = new JsonObject();
		packs.forEach(map::addProperty);
		root.add("packs", map);
		store.markDirty();
		store.save();
	}
}

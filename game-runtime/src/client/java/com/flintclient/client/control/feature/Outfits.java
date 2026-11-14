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
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The wardrobe's outfits: named sets of what to wear in each slot (and, optionally, the emote wheel), kept on this computer per
 * account in {@code config/flintclient/outfits.json}. Wearing one asks the Flint Store for each slot that differs, as picks in
 * the wardrobe do; an item the account no longer owns is skipped. Render thread; platform-free.
 */
public final class Outfits {
	public static final int SCHEMA = 1;
	/** Outfits per account at most. */
	public static final int MAX = 24;
	public static final int MAX_NAME = 24;

	/** One outfit: slot → item id ("" for a slot it leaves empty; a slot not named stays as it is). */
	public record Outfit(String name, Map<String, String> slots, long savedAt) {
	}

	private static @Nullable Outfits shared;

	private final @Nullable JsonStore file;
	private final Map<String, List<Outfit>> byAccount = new LinkedHashMap<>();

	/** Outfits read from {@code file} (null: in memory only, for tests and before start-up finished). */
	public Outfits(@Nullable Path file) {
		this.file = file == null ? null : new JsonStore(file, SCHEMA);
		if (this.file != null) {
			read(this.file.root());
		}
	}

	public static Outfits get() {
		Outfits o = shared;
		if (o == null) {
			Path dir = Features.flintDir();
			o = new Outfits(dir == null ? null : dir.resolve("outfits.json"));
			shared = o;
		}
		return o;
	}

	private void read(JsonObject root) {
		JsonElement accounts = root.get("accounts");
		if (accounts == null || !accounts.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : accounts.getAsJsonObject().entrySet()) {
			if (!e.getKey().matches("[0-9a-f]{32}") || !e.getValue().isJsonArray()) {
				continue;
			}
			List<Outfit> list = new ArrayList<>();
			for (JsonElement o : e.getValue().getAsJsonArray()) {
				Outfit outfit = readOne(o);
				if (outfit != null && list.size() < MAX) {
					list.add(outfit);
				}
			}
			byAccount.put(e.getKey(), list);
		}
	}

	private static @Nullable Outfit readOne(JsonElement e) {
		if (!e.isJsonObject()) {
			return null;
		}
		JsonObject o = e.getAsJsonObject();
		JsonElement name = o.get("name");
		JsonElement slots = o.get("slots");
		if (name == null || !name.isJsonPrimitive() || slots == null || !slots.isJsonObject()) {
			return null;
		}
		Map<String, String> worn = new LinkedHashMap<>();
		for (String slot : CosmeticKinds.SLOTS) {
			JsonElement id = slots.getAsJsonObject().get(slot);
			if (id != null && id.isJsonPrimitive()) {
				String v = id.getAsString();
				if (v.isEmpty() || v.matches("[0-9a-fA-F-]{36}")) {
					worn.put(slot, v.toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		JsonElement at = o.get("savedAt");
		long savedAt = at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isNumber() ? at.getAsLong() : 0;
		return new Outfit(clip(name.getAsString()), Collections.unmodifiableMap(worn), savedAt);
	}

	private static String clip(String name) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length() && out.length() < MAX_NAME; i++) {
			char c = name.charAt(i);
			if (c >= ' ' && c != '§' && c != 0x7F) {
				out.append(c);
			}
		}
		String s = out.toString().strip();
		return s.isEmpty() ? "Outfit" : s;
	}

	/** The account's outfits, newest first. */
	public List<Outfit> of(String account) {
		return List.copyOf(byAccount.getOrDefault(account, List.of()));
	}

	/** A name not used yet: "Outfit 1", "Outfit 2"… */
	public String nextName(String account) {
		List<Outfit> list = byAccount.getOrDefault(account, List.of());
		for (int n = 1; ; n++) {
			String name = "Outfit " + n;
			if (list.stream().noneMatch(o -> o.name.equalsIgnoreCase(name))) {
				return name;
			}
		}
	}

	/** Saves {@code slots} as a new outfit at the top (the oldest past {@link #MAX} goes); false when it is full already. */
	public boolean save(String account, String name, Map<String, String> slots, long now) {
		List<Outfit> list = new ArrayList<>(byAccount.getOrDefault(account, List.of()));
		if (list.size() >= MAX) {
			return false;
		}
		Map<String, String> worn = new LinkedHashMap<>();
		for (String slot : CosmeticKinds.SLOTS) {
			worn.put(slot, slots.getOrDefault(slot, ""));
		}
		list.add(0, new Outfit(clip(name), Collections.unmodifiableMap(worn), now));
		byAccount.put(account, list);
		write();
		return true;
	}

	public void remove(String account, Outfit outfit) {
		List<Outfit> list = new ArrayList<>(byAccount.getOrDefault(account, List.of()));
		if (list.remove(outfit)) {
			byAccount.put(account, list);
			write();
		}
	}

	private void write() {
		if (file == null || file.readOnly()) {
			return;
		}
		JsonObject accounts = new JsonObject();
		for (Map.Entry<String, List<Outfit>> e : byAccount.entrySet()) {
			JsonArray list = new JsonArray();
			for (Outfit o : e.getValue()) {
				JsonObject one = new JsonObject();
				one.addProperty("name", o.name);
				JsonObject slots = new JsonObject();
				o.slots.forEach(slots::addProperty);
				one.add("slots", slots);
				one.addProperty("savedAt", o.savedAt);
				list.add(one);
			}
			accounts.add(e.getKey(), list);
		}
		file.root().add("accounts", accounts);
		file.markDirty();
		file.save();
	}
}

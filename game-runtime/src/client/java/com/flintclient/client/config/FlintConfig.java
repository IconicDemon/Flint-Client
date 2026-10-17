package com.flintclient.client.config;

import com.flintclient.client.FlintClient;
import com.flintclient.client.module.servers.QuickPlay;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Flint's in-game settings, in {@code config/flintclient.json} inside the profile. Read once; written only when the
 * player changes something (atomically, via a temporary file).
 *
 * <p>It holds the Quick Play pins ({@code "quickPlay"}: name and address of each, in order; no account data). A file
 * from before Quick Play kept five fixed slots ({@code "shortcuts"}, with nulls for empty ones): the slots the player
 * had assigned become pins and the empty ones are dropped; the old key is removed the next time the file is written.
 * Keys this version doesn't know are kept.
 */
public final class FlintConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FlintConfig instance;

	private final Path file;
	private JsonObject root = new JsonObject();
	private List<QuickPlay.Entry> quickPlay = List.of();

	FlintConfig(Path file) {
		this.file = file;
	}

	public static FlintConfig get() {
		if (instance == null) {
			instance = new FlintConfig(FabricLoader.getInstance().getConfigDir().resolve("flintclient.json"));
			instance.load();
		}
		return instance;
	}

	/** The pinned servers, in order (empty until the player pins one). */
	public List<QuickPlay.Entry> quickPlay() {
		return quickPlay;
	}

	/** Saves {@code pins} (when they differ from what's saved). */
	public void setQuickPlay(List<QuickPlay.Entry> pins) {
		List<QuickPlay.Entry> next = List.copyOf(pins);
		if (next.equals(quickPlay) && !root.has("shortcuts")) {
			return;
		}
		quickPlay = next;
		save();
	}

	void load() {
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return;
			}
			root = parsed.getAsJsonObject();
			if (root.has("quickPlay") && root.get("quickPlay").isJsonArray()) {
				quickPlay = QuickPlay.migrate(entries(root.getAsJsonArray("quickPlay")));
			} else if (root.has("shortcuts") && root.get("shortcuts").isJsonArray()) {
				quickPlay = QuickPlay.migrate(entries(root.getAsJsonArray("shortcuts")));
			}
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Couldn't read {}; using defaults", file, e);
		}
	}

	/** Pins (or old slots) as written: objects with an address (a missing name is the address); anything else is null. */
	private static List<QuickPlay.@Nullable Entry> entries(JsonArray array) {
		List<QuickPlay.@Nullable Entry> out = new ArrayList<>();
		for (JsonElement element : array) {
			if (element.isJsonObject() && element.getAsJsonObject().has("address") && element.getAsJsonObject().get("address").isJsonPrimitive()) {
				JsonObject slot = element.getAsJsonObject();
				String address = slot.get("address").getAsString();
				String name = slot.has("name") && slot.get("name").isJsonPrimitive() ? slot.get("name").getAsString() : address;
				out.add(new QuickPlay.Entry(name, address));
			} else {
				out.add(null);
			}
		}
		return out;
	}

	private void save() {
		JsonArray pins = new JsonArray();
		for (QuickPlay.Entry pin : quickPlay) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", pin.name());
			entry.addProperty("address", pin.address());
			pins.add(entry);
		}
		root.remove("shortcuts");
		root.add("quickPlay", pins);
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			FlintClient.LOGGER.warn("Couldn't save {}", file, e);
		}
	}
}

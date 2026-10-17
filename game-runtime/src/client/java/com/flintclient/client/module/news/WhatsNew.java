package com.flintclient.client.module.news;

import com.flintclient.client.FlintClient;
import com.flintclient.client.util.FlintInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The release notes bundled with this build (the same file the launcher's news uses), read once. Fully
 * offline: there is no live feed, so the card is always true for the installed version.
 */
public final class WhatsNew {
	public record Note(String version, String date, String title, String summary, List<String> points) {
	}

	private static boolean loaded;
	private static @Nullable Note note;

	private WhatsNew() {
	}

	/** The notes for the installed Flint version, or the newest bundled notes; null if none could be read. */
	public static @Nullable Note current() {
		if (!loaded) {
			loaded = true;
			note = load();
		}
		return note;
	}

	private static @Nullable Note load() {
		try (InputStream in = WhatsNew.class.getResourceAsStream("/assets/flintclient/whats_new.json")) {
			if (in == null) {
				return null;
			}
			JsonArray all = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
			JsonObject chosen = null;
			for (JsonElement element : all) {
				JsonObject entry = element.getAsJsonObject();
				if (chosen == null) {
					chosen = entry;
				}
				if (FlintInfo.flintVersion().equals(text(entry, "version"))) {
					chosen = entry;
					break;
				}
			}
			if (chosen == null) {
				return null;
			}
			List<String> points = new ArrayList<>();
			if (chosen.has("points")) {
				chosen.getAsJsonArray("points").forEach(p -> points.add(p.getAsString()));
			}
			return new Note(text(chosen, "version"), text(chosen, "date"), text(chosen, "title"), text(chosen, "summary"), List.copyOf(points));
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Couldn't read the bundled release notes", e);
			return null;
		}
	}

	private static String text(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}
}

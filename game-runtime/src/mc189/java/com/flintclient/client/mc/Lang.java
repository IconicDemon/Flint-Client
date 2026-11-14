package com.flintclient.client.mc;

import com.flintclient.shaded.gson.JsonElement;
import com.flintclient.shaded.gson.JsonObject;
import com.flintclient.shaded.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.resource.language.I18n;
import org.apache.logging.log4j.LogManager;

/**
 * The words behind a translation key on Minecraft 1.8.9: Flint's own (its en_us.json, the file every line ships, read once
 * from this jar), else Minecraft 1.8.9's own translation of the key, else the few newer keys Flint's menu names that 1.8.9
 * doesn't have, in English. Render thread.
 */
final class Lang {
	/** Newer Minecraft's keys Flint's menu uses that 1.8.9 lacks (or words differently), as newer Minecraft says them. */
	private static final Map<String, String> NEWER = Map.of(
		"gui.back", "Back",
		"menu.quit", "Quit Game",
		"menu.singleplayer", "Singleplayer",
		"menu.multiplayer", "Multiplayer",
		"menu.playdemo", "Play Demo World");
	private static Map<String, String> flint;

	private Lang() {
	}

	static String get(String key) {
		String own = flint().get(key);
		if (own != null) {
			return own;
		}
		return vanilla(key);
	}

	/** Minecraft 1.8.9's translation of {@code key}, or newer Minecraft's English words when 1.8.9 has none. */
	static String vanilla(String key) {
		String found = I18n.translate(key);
		if (found != null && !found.equals(key)) {
			return found;
		}
		return NEWER.getOrDefault(key, key);
	}

	private static Map<String, String> flint() {
		Map<String, String> map = flint;
		if (map == null) {
			map = new HashMap<>();
			try (InputStream in = Lang.class.getResourceAsStream("/assets/flintclient/lang/en_us.json")) {
				if (in != null) {
					JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : root.entrySet()) {
						if (e.getValue().isJsonPrimitive()) {
							map.put(e.getKey(), e.getValue().getAsString());
						}
					}
				}
			} catch (Exception e) {
				LogManager.getLogger("Flint").warn("Flint: couldn't read its translations", e);
			}
			flint = map;
		}
		return map;
	}
}

package com.flintclient.client.hotload;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which Fabric mods can go into the running game with no restart (see {@link HotLoader}). Pure: it reads a jar's
 * {@code fabric.mod.json}, its file names and the ids of the jars it nests, and says yes, or no with the reason. A "no" means
 * the Library uses Instant Swap for that mod.
 *
 * <p>A mod qualifies when all of these hold:
 * <ul>
 * <li>it declares no mixins and no access widener or class tweaker: those change classes as they load, and the game's
 * classes are loaded already (a JVM can't add methods or fields to a loaded class);</li>
 * <li>no language adapter (other mods' entrypoints were made with the adapters known at start);</li>
 * <li>no {@code main}, {@code server} or {@code preLaunch} entrypoint, no early riser: a main entrypoint registers blocks,
 * items or entities, and the registries froze at start; pre-launch has passed;</li>
 * <li>no {@code data/}: datapack content (recipes, tags, worldgen) needs a world reload;</li>
 * <li>under {@code assets/}, only translations ({@code assets/NS/lang/*.json}, 1.8.9's {@code *.lang}) and pictures directly in {@code assets/NS/}
 * (the mod's icon): textures, models, sounds and shaders would need a resource reload (the loading screen);</li>
 * <li>every jar it nests is a mod already loaded (a library the game has);</li>
 * <li>it runs on the client ({@code environment} "*" or "client") and isn't loaded already.</li>
 * </ul>
 * Its dependencies (and breaks) are checked against the loaded mods' versions by {@link HotLoader}, with Fabric's own matcher.
 */
public final class HotLoadRule {
	/** Entrypoints that run before the point a hot load happens at, or that register content. */
	static final Set<String> BLOCKED_ENTRYPOINTS = Set.of("main", "server", "preLaunch", "mm:early_risers", "fabric-gametest");

	private HotLoadRule() {
	}

	/** The rule's answer: {@code ok}, else why not (one short line, for the log). */
	public record Verdict(boolean ok, String reason) {
		static Verdict yes() {
			return new Verdict(true, "qualifies");
		}

		static Verdict no(String reason) {
			return new Verdict(false, reason);
		}
	}

	/**
	 * @param fmj the jar's {@code fabric.mod.json}
	 * @param entries every file name in the jar ({@code a/b/c.class}; folders may be listed with a trailing slash)
	 * @param nestedIds the mod ids of the jars it nests ({@code jars}), read from their own {@code fabric.mod.json}
	 * @param loaded whether a mod id is loaded in this game (its own id or one it provides)
	 */
	public static Verdict check(JsonObject fmj, Collection<String> entries, Collection<String> nestedIds, Predicate<String> loaded) {
		if (fmj == null) {
			return Verdict.no("no fabric.mod.json");
		}
		int schema = fmj.has("schemaVersion") && fmj.get("schemaVersion").isJsonPrimitive() ? fmj.get("schemaVersion").getAsInt() : 0;
		if (schema != 1) {
			return Verdict.no("fabric.mod.json schema " + schema);
		}
		String id = string(fmj, "id");
		if (id == null || id.isBlank()) {
			return Verdict.no("no mod id");
		}
		if (loaded.test(id)) {
			return Verdict.no(id + " is loaded already");
		}
		String environment = string(fmj, "environment");
		if (environment != null && !environment.equals("*") && !environment.equals("client")) {
			return Verdict.no("environment " + environment);
		}
		if (nonEmpty(fmj, "mixins")) {
			return Verdict.no("declares mixins");
		}
		if (nonEmpty(fmj, "accessWidener") || nonEmpty(fmj, "classTweaker") || nonEmpty(fmj, "classTweakers")) {
			return Verdict.no("declares an access widener");
		}
		if (nonEmpty(fmj, "languageAdapters")) {
			return Verdict.no("declares a language adapter");
		}
		if (fmj.has("entrypoints") && fmj.get("entrypoints").isJsonObject()) {
			for (String key : fmj.getAsJsonObject("entrypoints").keySet()) {
				if (BLOCKED_ENTRYPOINTS.contains(key) && nonEmpty(fmj.getAsJsonObject("entrypoints"), key)) {
					return Verdict.no("has a " + key + " entrypoint");
				}
			}
		} else if (fmj.has("entrypoints")) {
			return Verdict.no("unreadable entrypoints");
		}
		for (String raw : entries) {
			String name = raw.replace('\\', '/');
			if (name.endsWith("/")) {
				continue;
			}
			if (name.startsWith("data/")) {
				return Verdict.no("has datapack content (" + name + ")");
			}
			if (name.startsWith("assets/") && !plainAsset(name)) {
				return Verdict.no("has resources that need a reload (" + name + ")");
			}
		}
		for (String nested : nestedIds) {
			if (nested == null || !loaded.test(nested)) {
				return Verdict.no("nests " + nested + ", which isn't loaded");
			}
		}
		return Verdict.yes();
	}

	/** {@code assets/NS/lang/*.json}, or a picture directly in {@code assets/NS/}. */
	static boolean plainAsset(String name) {
		String[] parts = name.split("/");
		if (parts.length == 3) {
			return parts[2].toLowerCase(Locale.ROOT).endsWith(".png");
		}
		// .lang: Minecraft 1.8.9's translations (its hot loader adds them as 26.x's adds .json).
		return parts.length == 4 && parts[2].equals("lang") && (parts[3].endsWith(".json") || parts[3].endsWith(".lang"));
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static boolean nonEmpty(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return false;
		}
		if (e.isJsonArray()) {
			return !((JsonArray) e).isEmpty();
		}
		if (e.isJsonObject()) {
			return e.getAsJsonObject().size() != 0;
		}
		return !e.getAsString().isBlank();
	}

	/** The ids a {@code depends} block names that aren't provided by the game itself (for the log). */
	static List<String> dependencyIds(JsonObject fmj) {
		if (!fmj.has("depends") || !fmj.get("depends").isJsonObject()) {
			return List.of();
		}
		return fmj.getAsJsonObject("depends").keySet().stream().filter(k -> !k.equals("minecraft") && !k.equals("java") && !k.equals("fabricloader")).toList();
	}
}

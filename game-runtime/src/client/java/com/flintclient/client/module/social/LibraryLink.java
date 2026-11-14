package com.flintclient.client.module.social;

import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The in-game Library's requests to the Flint app (the launcher's {@code content/ingame.rs}, through the game link): the
 * profile's counts, a search on Modrinth or CurseForge of what fits this game, what an install would add, installing and
 * removing, and the hits' icons. The launcher works on the profile this game was started for, with its own content code: the
 * game downloads nothing itself. Answers are untrusted and checked as {@link MenuLink} checks its own. Calls block: run them
 * off the render thread ({@link MenuFeeds#run}). Platform-free.
 */
public final class LibraryLink {
	private static final int QUICK_MILLIS = 35_000;
	/** An install downloads and checks every file first (the launcher allows 10 minutes). */
	private static final int INSTALL_MILLIS = 610_000;
	private static final int MAX_REPLY = 256 * 1024;
	private static final int MAX_ART_REPLY = 2 * 1024 * 1024;
	public static final List<String> KINDS = List.of("mod", "resourcepack", "shader");
	public static final List<String> SOURCES = List.of("modrinth", "curseforge");

	private final LauncherLink link;

	public LibraryLink(LauncherLink link) {
		this.link = link;
	}

	/** This game's link to the Flint app, or null when Flint didn't start it. */
	public static @Nullable LibraryLink get() {
		LauncherLink link = LauncherLink.get();
		return link == null ? null : new LibraryLink(link);
	}

	/** One thing the profile has (Your mods): its kind, file, name, version, whether it is on, and its icon's address. */
	public record Item(String kind, String fileName, String title, String version, boolean enabled, @Nullable String icon) {
	}

	/** The profile's counts and what it has. {@code fabric}: it takes mods and shader packs. */
	public record Counts(String gameVersion, boolean fabric, int mods, int resourcepacks, int shaders, boolean iris, List<Item> items) {
		public static final Counts NONE = new Counts("", true, -1, -1, -1, false, List.of());

		public int of(String kind) {
			return switch (kind) {
				case "mod" -> mods;
				case "resourcepack" -> resourcepacks;
				default -> shaders;
			};
		}
	}

	/** What the profile has of a hit: its file. */
	public record Have(String fileName, boolean enabled) {
	}

	/** One search hit. {@code icon}: an address on Modrinth's or CurseForge's picture host, to ask {@link #art} for. */
	public record Hit(String id, String slug, String title, String author, String description, long downloads, @Nullable String icon,
		@Nullable Have installed) {
		public Hit withInstalled(@Nullable Have have) {
			return new Hit(id, slug, title, author, description, downloads, icon, have);
		}
	}

	/** A page of hits. {@code unavailable}: why the source couldn't be searched now (CurseForge offline, no mods here). */
	public record Page(List<Hit> hits, long total, int offset, @Nullable String unavailable) {
	}

	/** One file an install adds (the project's own, {@code root}, or a library it needs). */
	public record Part(String title, String kind, String version, boolean root) {
	}

	/**
	 * An install's result: the project's file, whether a mod went in (Instant Swap takes over when the Library closes unless
	 * the mods load live), and the mod files put in, libraries first.
	 */
	public record Installed(@Nullable String fileName, List<String> added, boolean swap, List<String> modFiles) {
	}

	public Counts counts() throws LinkException {
		JsonObject r = link.request("libraryState", new JsonObject(), QUICK_MILLIS, MAX_REPLY);
		List<Item> items = new ArrayList<>();
		for (JsonObject o : objects(r, "items", 600)) {
			String kind = text(o, "kind", 16);
			String file = SocialLink.string(o, "fileName");
			if (!KINDS.contains(kind) || file == null || !plainName(file)) {
				continue;
			}
			String title = text(o, "title", 60);
			String icon = MenuLink.https(SocialLink.string(o, "icon"));
			if (icon != null && !(icon.startsWith("https://cdn.modrinth.com/") || icon.startsWith("https://media.forgecdn.net/"))) {
				icon = null;
			}
			items.add(new Item(kind, file, title.isEmpty() ? file : title, text(o, "version", 40), !o.has("enabled") || bool(o, "enabled"), icon));
		}
		return new Counts(text(r, "gameVersion", 24), bool(r, "fabric"), count(r, "mods"), count(r, "resourcepacks"), count(r, "shaders"), bool(r, "iris"),
			List.copyOf(items));
	}

	public Page search(String source, String kind, String query, int offset, @Nullable String sort) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("source", SOURCES.contains(source) ? source : "modrinth");
		fields.addProperty("kind", KINDS.contains(kind) ? kind : "mod");
		fields.addProperty("query", query.length() > 120 ? query.substring(0, 120) : query);
		fields.addProperty("offset", Math.max(0, Math.min(9000, offset)));
		if (sort != null) {
			fields.addProperty("sort", sort);
		}
		return readPage(link.request("librarySearch", fields, QUICK_MILLIS, MAX_REPLY));
	}

	public List<Part> plan(String kind, String id) throws LinkException {
		JsonObject reply = link.request("libraryPlan", project(kind, id), QUICK_MILLIS, 64 * 1024);
		List<Part> parts = new ArrayList<>();
		for (JsonObject p : objects(reply, "parts", 40)) {
			String title = text(p, "title", 60);
			String k = text(p, "kind", 16);
			if (!title.isEmpty() && KINDS.contains(k)) {
				parts.add(new Part(title, k, text(p, "version", 40), bool(p, "root")));
			}
		}
		return List.copyOf(parts);
	}

	public Installed install(String kind, String id) throws LinkException {
		JsonObject reply = link.request("libraryInstall", project(kind, id), INSTALL_MILLIS, 64 * 1024);
		String file = SocialLink.string(reply, "fileName");
		List<String> added = new ArrayList<>();
		JsonElement list = reply.get("added");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				if (e.isJsonPrimitive() && added.size() < 20) {
					added.add(SocialLink.clean(e.getAsString(), 60, false));
				}
			}
		}
		List<String> mods = new ArrayList<>();
		JsonElement modList = reply.get("modFiles");
		if (modList != null && modList.isJsonArray()) {
			for (JsonElement e : modList.getAsJsonArray()) {
				if (e.isJsonPrimitive() && plainName(e.getAsString()) && e.getAsString().endsWith(".jar") && mods.size() < 20) {
					mods.add(e.getAsString());
				}
			}
		}
		return new Installed(file != null && plainName(file) ? file : null, List.copyOf(added), bool(reply, "swap"), List.copyOf(mods));
	}

	/** A mod that needs the one being taken out. */
	public record Need(String title, String fileName) {
	}

	/**
	 * A removal's answer: done ({@code removed}, and whether a mod changed so Instant Swap takes over when the Library closes),
	 * or not done because other mods need it ({@code needs}): the player is asked, and {@code also} takes them out together.
	 */
	public record Removed(boolean removed, boolean swap, String title, List<Need> needs) {
	}

	public Removed remove(String kind, String fileName, boolean also) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("kind", kind);
		fields.addProperty("file", fileName);
		fields.addProperty("also", also);
		JsonObject reply = link.request("libraryRemove", fields, QUICK_MILLIS, 16 * 1024);
		List<Need> needs = new ArrayList<>();
		JsonElement list = reply.get("needs");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				if (e.isJsonObject() && needs.size() < 20) {
					String file = SocialLink.string(e.getAsJsonObject(), "fileName");
					String title = SocialLink.string(e.getAsJsonObject(), "title");
					if (file != null && plainName(file)) {
						needs.add(new Need(title == null ? file : SocialLink.clean(title, 60, false), file));
					}
				}
			}
		}
		String title = SocialLink.string(reply, "title");
		return new Removed(bool(reply, "removed"), bool(reply, "swap"), title == null ? "" : SocialLink.clean(title, 60, false), List.copyOf(needs));
	}

	public MenuLink.Art art(String url) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("url", url);
		return MenuLink.readArt(link.request("libraryArt", fields, QUICK_MILLIS, MAX_ART_REPLY));
	}

	// ---------- Instant Swap ----------

	/** How the new game is coming along: none, preparing, ready (with {@code sequential}), failed (with the reason). */
	public record SwapStatus(String state, boolean sequential, @Nullable String reason, List<String> titles) {
	}

	public SwapStatus swapStatus() throws LinkException {
		JsonObject r = link.request("swapStatus", new JsonObject(), QUICK_MILLIS, 16 * 1024);
		String state = text(r, "state", 20);
		List<String> titles = new ArrayList<>();
		JsonElement list = r.get("titles");
		if (list != null && list.isJsonArray()) {
			for (JsonElement e : list.getAsJsonArray()) {
				if (e.isJsonPrimitive() && titles.size() < 10) {
					titles.add(SocialLink.clean(e.getAsString(), 60, false));
				}
			}
		}
		String reason = SocialLink.string(r, "reason");
		return new SwapStatus(state.isEmpty() ? "none" : state, bool(r, "sequential"), reason == null ? null : SocialLink.clean(reason, 300, true),
			List.copyOf(titles));
	}

	/** The old game's handover: the world it was in, or the server, and its window. */
	public void swapGo(@Nullable String world, @Nullable String server, @Nullable JsonObject window) throws LinkException {
		JsonObject fields = new JsonObject();
		if (world != null) {
			fields.addProperty("world", world);
		}
		if (server != null) {
			fields.addProperty("server", server);
		}
		if (window != null) {
			fields.add("window", window);
		}
		link.request("swapGo", fields, QUICK_MILLIS, 16 * 1024);
	}

	/** The mods just added were loaded into this game live: the standby game started for them can end. */
	public void swapDrop() throws LinkException {
		link.request("swapDrop", new JsonObject(), QUICK_MILLIS, 16 * 1024);
	}

	/** The standby game is in the world and in front: the old one can end. */
	public void swapJoined(int framesPresented) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("frames", framesPresented);
		link.request("swapJoined", fields, QUICK_MILLIS, 16 * 1024);
	}

	public void swapReady() throws LinkException {
		link.request("swapReady", new JsonObject(), QUICK_MILLIS, 16 * 1024);
	}

	/** The handover, or null while there is none yet; {@code quit} in it: this standby game isn't wanted any more. */
	public @Nullable JsonObject swapWait(int waitMillis) throws LinkException {
		JsonObject fields = new JsonObject();
		fields.addProperty("waitMs", waitMillis);
		JsonObject reply = link.request("swapWait", fields, waitMillis + 10_000, 16 * 1024);
		if (bool(reply, "quit")) {
			JsonObject quit = new JsonObject();
			quit.addProperty("quit", true);
			return quit;
		}
		JsonElement go = reply.get("go");
		return go != null && go.isJsonObject() ? go.getAsJsonObject() : null;
	}

	// ---------- reading ----------

	static Page readPage(JsonObject reply) {
		List<Hit> hits = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (JsonObject h : objects(reply, "hits", 50)) {
			String id = SocialLink.string(h, "id");
			String title = text(h, "title", 60);
			if (id == null || !id.matches("(cf:[0-9]{1,12}|[A-Za-z0-9._-]{1,64})") || title.isEmpty() || !seen.add(id)) {
				continue;
			}
			String icon = MenuLink.https(SocialLink.string(h, "icon"));
			if (icon != null && !(icon.startsWith("https://cdn.modrinth.com/") || icon.startsWith("https://media.forgecdn.net/"))) {
				icon = null;
			}
			Have have = null;
			JsonElement installed = h.get("installed");
			if (installed != null && installed.isJsonObject()) {
				String file = SocialLink.string(installed.getAsJsonObject(), "fileName");
				if (file != null && plainName(file)) {
					have = new Have(file, !installed.getAsJsonObject().has("enabled") || bool(installed.getAsJsonObject(), "enabled"));
				}
			}
			hits.add(new Hit(id, text(h, "slug", 64), title, text(h, "author", 40), text(h, "description", 200), Math.max(0, count(h, "downloads")), icon, have));
		}
		String unavailable = SocialLink.string(reply, "unavailable");
		return new Page(List.copyOf(hits), Math.max(0, count(reply, "total")), Math.max(0, count(reply, "offset")),
			unavailable == null ? null : unavailable.toLowerCase(Locale.ROOT));
	}

	/** A plain file name in a content folder (no folders, not hidden). */
	static boolean plainName(String name) {
		return !name.isEmpty() && name.length() <= 180 && !name.startsWith(".") && !name.contains("/") && !name.contains("\\") && !name.contains(":");
	}

	private static JsonObject project(String kind, String id) {
		JsonObject fields = new JsonObject();
		fields.addProperty("kind", kind);
		fields.addProperty("id", id);
		return fields;
	}

	private static String text(JsonObject o, String key, int max) {
		String value = SocialLink.string(o, key);
		return value == null ? "" : SocialLink.clean(value, max, false).strip();
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
	}

	private static int count(JsonObject o, String key) {
		JsonElement v = o.get(key);
		if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
			return -1;
		}
		double d = v.getAsDouble();
		return Double.isFinite(d) && d >= 0 ? (int) Math.min(Integer.MAX_VALUE, d) : -1;
	}

	private static List<JsonObject> objects(JsonObject object, String key, int max) {
		JsonElement value = object.get(key);
		List<JsonObject> out = new ArrayList<>();
		if (value != null && value.isJsonArray()) {
			for (JsonElement e : value.getAsJsonArray()) {
				if (out.size() >= max) {
					break;
				}
				if (e.isJsonObject()) {
					out.add(e.getAsJsonObject());
				}
			}
		}
		return out;
	}
}

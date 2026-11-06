package com.flintclient.client.control.store;

import com.flintclient.client.FlintLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Packs the Flint app put into this game's folders while it runs (launcher {@code app/src-tauri/src/content/livepacks.rs}):
 * {@value #FILE} in the game directory, rewritten atomically after each install or removal:
 *
 * <pre>{@code
 * {"schema": 1, "revision": 1762380000123,
 *  "changes": [{"revision": 1762380000123, "kind": "resourcepack", "file": "Dirt Tint.zip", "title": "Dirt Tint",
 *               "action": "added", "enable": true}]}
 * }</pre>
 *
 * <p>The file keeps the launcher's newest few changes, each with the revision it was written at (milliseconds since the epoch,
 * always above the one before). {@link #poll} hands out each change once: the ones newer than the last it handed out and newer
 * than this game's start, so a file left from an earlier session is never replayed. A change names a plain file in its kind's
 * own folder ({@code resourcepacks}, {@code shaderpacks}, or a world's {@code datapacks}): one that names anything else (a path,
 * a hidden or reserved name), of an unknown kind or action, is left out; a file that is missing, larger than {@link #MAX_BYTES},
 * not JSON or of another {@code schema} gives nothing. Only a hint: what the game does with a change it checks again itself.
 *
 * <p>{@link #poll} looks at the file's modification time and size (one {@code stat}) and reads it only when either changed; the
 * line's code calls it once a second. Game thread. Pure Java (every line, 1.8.9 included).
 */
public final class PackHandoff {
	public static final String FILE = ".flint-packs.json";
	/** The file layout this runtime reads. */
	static final int SCHEMA = 1;
	static final int MAX_BYTES = 64 * 1024;
	/** The most changes one read hands out (the launcher keeps 16). */
	static final int MAX_CHANGES = 32;
	static final int MAX_NAME = 180;
	static final int MAX_TITLE = 64;
	/** Windows' device names, which no file in a folder can have. */
	private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

	/** What a change is about. */
	public enum Kind {
		RESOURCE_PACK("resourcepack"),
		SHADER_PACK("shaderpack"),
		DATA_PACK("datapack");

		private final String id;

		Kind(String id) {
			this.id = id;
		}

		static @Nullable Kind of(@Nullable String id) {
			for (Kind kind : values()) {
				if (kind.id.equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	/**
	 * One change: {@code file} was put into (or, {@code removed}, taken out of) its kind's folder; {@code enable}: the player
	 * asked for it to be turned on in the game now; {@code world}: a data pack's world (its folder in {@code saves}).
	 */
	public record Change(long revision, Kind kind, String file, String title, boolean removed, boolean enable, @Nullable String world) {
	}

	private final Path gameDir;
	private final Path file;
	private long modified = Long.MIN_VALUE;
	private long size = -2;
	private boolean checked;
	/** The newest revision handed out (at first: when this game started). */
	private long handedOut;
	private boolean warned;

	/**
	 * The handoff in {@code gameDir} (the profile's folder: Minecraft's {@code --gameDir}); {@code startedAt}: when this game
	 * started (ms since the epoch), so changes written before are never applied.
	 */
	public PackHandoff(Path gameDir, long startedAt) {
		this.gameDir = gameDir;
		this.file = gameDir.resolve(FILE);
		this.handedOut = startedAt;
	}

	/** The changes that arrived since the last call, oldest first (each once); none when the file didn't change. */
	public List<Change> poll() {
		long m;
		long s;
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			m = attributes.isRegularFile() ? attributes.lastModifiedTime().toMillis() : -1;
			s = attributes.isRegularFile() ? attributes.size() : -1;
		} catch (NoSuchFileException e) {
			m = -1;
			s = -1;
		} catch (IOException | RuntimeException e) {
			m = -1;
			s = -1;
		}
		if (checked && m == modified && s == size) {
			return List.of();
		}
		List<Change> read = List.of();
		if (s > MAX_BYTES) {
			warnOnce(FILE + " is too large to be the Flint app's pack handoff; ignoring it");
		} else if (s >= 0) {
			String text;
			try {
				text = Files.readString(file, StandardCharsets.UTF_8);
			} catch (IOException | RuntimeException e) {
				// Being replaced (or briefly locked): read again next time.
				checked = false;
				return List.of();
			}
			List<Change> parsed = parse(text);
			if (parsed == null) {
				warnOnce(FILE + " isn't a pack handoff this runtime reads; ignoring it");
			} else {
				read = parsed;
			}
		}
		checked = true;
		modified = m;
		size = s;
		List<Change> fresh = new ArrayList<>();
		long newest = handedOut;
		for (Change change : read) {
			if (change.revision() > handedOut) {
				fresh.add(change);
				newest = Math.max(newest, change.revision());
			}
		}
		handedOut = newest;
		return fresh;
	}

	private void warnOnce(String message) {
		if (!warned) {
			warned = true;
			FlintLog.LOGGER.warn("Flint: {}", message);
		}
	}

	/**
	 * The usable changes in {@code json}, oldest first (at most {@link #MAX_CHANGES}, the newest), or null when this runtime
	 * can't read it at all (not JSON, another schema, no change list).
	 */
	static @Nullable List<Change> parse(String json) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(json);
		} catch (RuntimeException e) {
			return null;
		}
		if (!parsed.isJsonObject()) {
			return null;
		}
		JsonObject root = parsed.getAsJsonObject();
		Long schema = JsonFields.integer(root, "schema");
		JsonElement list = root.get("changes");
		if (schema == null || schema != SCHEMA || list == null || !list.isJsonArray()) {
			return null;
		}
		JsonArray array = list.getAsJsonArray();
		List<Change> out = new ArrayList<>();
		for (int i = Math.max(0, array.size() - MAX_CHANGES); i < array.size(); i++) {
			JsonElement item = array.get(i);
			Change change = item.isJsonObject() ? change(item.getAsJsonObject()) : null;
			if (change != null) {
				out.add(change);
			}
		}
		out.sort((a, b) -> Long.compare(a.revision(), b.revision()));
		return out;
	}

	private static @Nullable Change change(JsonObject o) {
		Long revision = JsonFields.integer(o, "revision");
		Kind kind = Kind.of(JsonFields.string(o, "kind"));
		String name = JsonFields.string(o, "file");
		String action = JsonFields.string(o, "action");
		if (revision == null || revision < 0 || kind == null || name == null || !plainName(name) || action == null) {
			return null;
		}
		boolean removed;
		if (action.equals("added")) {
			removed = false;
		} else if (action.equals("removed")) {
			removed = true;
		} else {
			return null;
		}
		String world = null;
		if (kind == Kind.DATA_PACK) {
			world = JsonFields.string(o, "world");
			if (world == null || !plainName(world)) {
				return null;
			}
		}
		JsonElement enable = o.get("enable");
		boolean on = enable != null && enable.isJsonPrimitive() && enable.getAsJsonPrimitive().isBoolean() && enable.getAsBoolean();
		return new Change(revision, kind, name, title(JsonFields.string(o, "title"), name), removed, on && !removed && kind != Kind.DATA_PACK, world);
	}

	/**
	 * A name the game may show: one line of printable text without Minecraft's formatting sign, at most {@link #MAX_TITLE}
	 * characters; the file's name without {@code .zip} when there is none.
	 */
	static String title(@Nullable String title, String file) {
		StringBuilder out = new StringBuilder();
		if (title != null) {
			title.codePoints().filter(c -> !Character.isISOControl(c) && c != 0xA7 && Character.getType(c) != Character.FORMAT).forEach(out::appendCodePoint);
		}
		String text = out.toString().strip();
		if (text.isEmpty()) {
			text = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
		}
		return text.codePointCount(0, text.length()) <= MAX_TITLE ? text : text.substring(0, text.offsetByCodePoints(0, MAX_TITLE - 1)).strip() + "…";
	}

	/**
	 * Whether {@code name} is a plain file name: no folders or drive, not hidden, not {@code .} or {@code ..}, no control
	 * characters, no Windows device name, not ending in a dot or a space (Windows would read another name), at most
	 * {@link #MAX_NAME} characters.
	 */
	public static boolean plainName(@Nullable String name) {
		if (name == null || name.isEmpty() || name.length() > MAX_NAME || !name.equals(name.strip())) {
			return false;
		}
		if (name.startsWith(".") || name.endsWith(".") || name.endsWith(" ") || RESERVED.matcher(name).matches()) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '/' || c == '\\' || c == ':' || c < 0x20 || c == 0x7F) {
				return false;
			}
		}
		return true;
	}

	/** The folder a change's file is in: {@code resourcepacks}, {@code shaderpacks}, or its world's {@code datapacks}. */
	public static Path folder(Path gameDir, Change change) {
		return switch (change.kind()) {
			case RESOURCE_PACK -> gameDir.resolve("resourcepacks");
			case SHADER_PACK -> gameDir.resolve("shaderpacks");
			case DATA_PACK -> gameDir.resolve("saves").resolve(change.world() == null ? "" : change.world()).resolve("datapacks");
		};
	}

	/** Where a change's file is, or null when it wouldn't be a file directly in its folder (checked again, after {@link #parse}). */
	public static @Nullable Path path(Path gameDir, Change change) {
		if (!plainName(change.file()) || change.kind() == Kind.DATA_PACK && !plainName(change.world())) {
			return null;
		}
		Path folder = folder(gameDir, change).toAbsolutePath().normalize();
		Path path = folder.resolve(change.file()).normalize();
		return folder.equals(path.getParent()) ? path : null;
	}

	/** The game directory this handoff is in. */
	public Path gameDir() {
		return gameDir;
	}
}

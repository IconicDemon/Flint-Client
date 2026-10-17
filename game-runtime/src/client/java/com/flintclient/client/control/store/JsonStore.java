package com.flintclient.client.control.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One JSON settings file, read once and written only on purpose. The rules, so a player never loses settings:
 * <ul>
 * <li>Writes go to {@code <file>.tmp} and are then moved over the file atomically (a plain replace only where the
 * file system can't move atomically), so a crash mid-write never leaves half a file.</li>
 * <li>The parsed object is kept and saved back with only the known fields updated, so keys this version
 * doesn't know (from a newer Flint, or added by hand) survive.</li>
 * <li>A file that isn't a JSON object (or isn't UTF-8 text) is copied (never moved) to
 * {@code <name>.damaged-<yyyyMMdd-HHmmss>.json}, and defaults are used with a {@link #notice()}. The original is
 * replaced only by a later deliberate save.</li>
 * <li>A file from a newer schema, or with a schema this version can't make sense of, is read for what this
 * version understands and never written.</li>
 * <li>A missing file means defaults, no notice, and nothing written until something changes.</li>
 * </ul>
 * Single values that are wrong are the owner's business (it reads each field with a default). Render thread
 * only; files are small and written only on player actions.
 */
public final class JsonStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("Flint Client");
	/** Nulls are written, not dropped: an unknown key holding null is still someone's data. */
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	static final String SCHEMA_KEY = "schema";
	/** {@link #fileSchema()} for a schema that is there but isn't a whole number from 1 up. */
	public static final int UNKNOWN_SCHEMA = -1;

	/** What was found on disk. */
	public enum State {
		/** No file yet. */
		MISSING,
		/** Read normally. */
		LOADED,
		/** Not usable JSON; a copy was kept and defaults are in use. */
		DAMAGED,
		/** Written by a newer Flint (or with a schema that can't be understood); read-only. */
		NEWER,
		/** Couldn't be read or copied at all (an I/O error); read-only so it is never replaced blindly. */
		UNREADABLE
	}

	private final Path file;
	private final int schema;
	private final Clock clock;

	private boolean loaded;
	private JsonObject root = new JsonObject();
	private State state = State.MISSING;
	private int fileSchema;
	private @Nullable String notice;
	private @Nullable Path damagedCopy;
	private boolean dirty;

	/** A store for {@code file}, which this version writes as {@code schema}. */
	public JsonStore(Path file, int schema) {
		this(file, schema, Clock.systemDefaultZone());
	}

	JsonStore(Path file, int schema, Clock clock) {
		this.file = file;
		this.schema = schema;
		this.clock = clock;
	}

	public Path file() {
		return file;
	}

	/** The file's contents (read on first use). Update fields in place, then {@link #markDirty()}. */
	public JsonObject root() {
		load();
		return root;
	}

	public State state() {
		load();
		return state;
	}

	/**
	 * The schema the file says it has: 0 when there is no file, 1 when the file doesn't say, and
	 * {@link #UNKNOWN_SCHEMA} when it says something that isn't a whole number from 1 up.
	 */
	public int fileSchema() {
		load();
		return fileSchema;
	}

	/** A sentence for the Control Center when the file couldn't be used as is, otherwise null. */
	public @Nullable String notice() {
		load();
		return notice;
	}

	/** The copy made of a damaged file, or null. */
	public @Nullable Path damagedCopy() {
		load();
		return damagedCopy;
	}

	/**
	 * Makes sure a file that couldn't be used has a copy next to it before its owner deletes it: the copy made
	 * when it was read, or one made now (a file that couldn't be read at first may be readable now). Returns
	 * the copy, or null when no copy could be made, in which case the file must not be deleted.
	 */
	public @Nullable Path keepCopy() {
		load();
		if (damagedCopy == null && Files.exists(file)) {
			try {
				damagedCopy = copyOfDamaged();
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Couldn't keep a copy of {}", file, e);
			}
		}
		return damagedCopy;
	}

	/** True when this file must not be written (newer schema, or unreadable and not copied). */
	public boolean readOnly() {
		load();
		return state == State.NEWER || state == State.UNREADABLE;
	}

	public void markDirty() {
		dirty = true;
	}

	public boolean dirty() {
		return dirty;
	}

	/**
	 * Writes the file if something changed and writing is allowed. Returns true when the file on disk now
	 * matches memory (including when there was nothing to write).
	 */
	public boolean save() {
		load();
		if (!dirty) {
			return true;
		}
		if (readOnly()) {
			return false;
		}
		root.addProperty(SCHEMA_KEY, schema);
		try {
			writeAtomically(file, GSON.toJson(root) + "\n");
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Couldn't save {}", file, e);
			return false;
		}
		dirty = false;
		state = State.LOADED;
		fileSchema = schema;
		// The damaged original has now been replaced on purpose; the kept copy stays, but there is nothing left to warn about.
		notice = null;
		return true;
	}

	/** Reads the file once. */
	private void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		root = fresh();
		if (!Files.exists(file)) {
			state = State.MISSING;
			return;
		}
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (CharacterCodingException e) {
			// Not UTF-8 (saved by an editor in another encoding, say): unusable, but its bytes can still be copied aside.
			damaged();
			return;
		} catch (IOException | RuntimeException e) {
			// Not even readable: copying would fail too, so leave the file exactly as it is.
			LOGGER.warn("Couldn't read {}; using defaults and leaving the file alone", file, e);
			state = State.UNREADABLE;
			notice = name() + " couldn't be read, so Flint is using defaults and won't change it.";
			return;
		}
		if (text.startsWith("\uFEFF")) {
			// A byte order mark, as some Windows editors write: not part of the JSON.
			text = text.substring(1);
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(text);
		} catch (JsonParseException | IllegalStateException e) {
			parsed = null;
		}
		if (parsed == null || !parsed.isJsonObject()) {
			damaged();
			return;
		}
		JsonObject object = parsed.getAsJsonObject();
		fileSchema = schemaOf(object);
		root = object;
		if (fileSchema == UNKNOWN_SCHEMA) {
			state = State.NEWER;
			notice = name() + " has a version number Flint doesn't recognise; changes won't be saved.";
			LOGGER.warn("{} has an unrecognised schema {}; reading it without saving", file, object.get(SCHEMA_KEY));
			return;
		}
		if (fileSchema > schema) {
			state = State.NEWER;
			notice = "Saved by a newer Flint version; changes won't be saved.";
			LOGGER.warn("{} is from a newer Flint (schema {}, this version writes {}); reading it without saving", file, fileSchema, schema);
			return;
		}
		state = State.LOADED;
	}

	private void damaged() {
		try {
			damagedCopy = copyOfDamaged();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("{} couldn't be read, and copying it failed; using defaults and leaving the file alone", file, e);
			state = State.UNREADABLE;
			notice = name() + " couldn't be read, so Flint is using defaults and won't change it.";
			return;
		}
		state = State.DAMAGED;
		notice = name() + " couldn't be read. Flint saved a copy as " + damagedCopy.getFileName() + " and is using defaults.";
		LOGGER.warn("{} couldn't be read; kept a copy as {} and using defaults", file, damagedCopy.getFileName());
	}

	/** Copies the damaged file next to itself, reusing an identical earlier copy so restarts don't pile them up. */
	private Path copyOfDamaged() throws IOException {
		String stem = stem();
		Path dir = file.toAbsolutePath().getParent();
		try (DirectoryStream<Path> siblings = Files.newDirectoryStream(dir, stem + ".damaged-*.json")) {
			for (Path sibling : siblings) {
				if (Files.isRegularFile(sibling) && Files.mismatch(file, sibling) == -1L) {
					return sibling;
				}
			}
		}
		String base = stem + ".damaged-" + LocalDateTime.now(clock).format(STAMP);
		Path copy = dir.resolve(base + ".json");
		for (int n = 2; Files.exists(copy); n++) {
			copy = dir.resolve(base + "-" + n + ".json");
		}
		Files.copy(file, copy);
		return copy;
	}

	private JsonObject fresh() {
		JsonObject object = new JsonObject();
		object.addProperty(SCHEMA_KEY, schema);
		return object;
	}

	private String name() {
		return file.getFileName().toString();
	}

	private String stem() {
		String name = name();
		return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
	}

	/**
	 * The file's schema number. A missing one (or null) counts as 1, the first layout; a number written as text
	 * is read as that number; anything past {@code int} counts as the newest possible. Anything else (another
	 * type, a fraction, 0 or less) is {@link #UNKNOWN_SCHEMA}: the file can't be trusted to be ours to rewrite.
	 */
	static int schemaOf(JsonObject object) {
		JsonElement value = object.get(SCHEMA_KEY);
		if (value == null || value.isJsonNull()) {
			return 1;
		}
		if (!value.isJsonPrimitive()) {
			return UNKNOWN_SCHEMA;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		double number;
		if (primitive.isNumber()) {
			number = primitive.getAsDouble();
		} else if (primitive.isString()) {
			try {
				number = Double.parseDouble(primitive.getAsString().strip());
			} catch (NumberFormatException e) {
				return UNKNOWN_SCHEMA;
			}
		} else {
			return UNKNOWN_SCHEMA;
		}
		if (!(number >= 1) || number != Math.rint(number)) {
			return UNKNOWN_SCHEMA;
		}
		return number >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) number;
	}

	/**
	 * Writes {@code content} to {@code target} through {@code <target>.tmp} and an atomic move, creating parent
	 * folders. Where the file system can't move atomically, a plain replace is used instead. The temporary file
	 * never outlives a failed write.
	 */
	static void writeAtomically(Path target, String content) throws IOException {
		Path parent = target.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.writeString(temp, content, StandardCharsets.UTF_8);
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}

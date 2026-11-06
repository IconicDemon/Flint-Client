package com.flintclient.client.control.store;

import com.flintclient.client.FlintLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The launcher's Store handoff (launcher docs/STORE-INTEGRATION.md, "Game handoff (v1)"): {@value #FILE} in the game directory,
 * written by the launcher at every launch and rewritten while the game runs when the player equips or removes something:
 *
 * <pre>{@code
 * {"schema": 1, "contractVersion": 1, "storeBaseUrl": "https://store.flintclient.com",
 *  "publicCosmeticsUrl": "https://store.flintclient.com/api/v1/players/cosmetics/", "equipRevision": 0}
 * }</pre>
 *
 * <p>A hint, never proof of anything: it only says which Store to ask and when to ask about your own player again. A file
 * that is missing, unreadable, larger than {@link #MAX_BYTES}, of another {@code schema} or {@code contractVersion}, with a
 * {@code storeBaseUrl} that isn't {@code https} (or {@code http} on localhost/127.0.0.1), or a {@code publicCosmeticsUrl} that
 * isn't that Store's lookup, is ignored: the built-in Store is used. {@code equipRevision} is compared for inequality only (it
 * goes back to 0 when the launcher restarts).
 *
 * <p>{@link #poll} looks at the file's modification time and size (one {@code stat}) and reads it only when either changed. Game
 * thread. Pure Java.
 */
public final class StoreHandoff {
	public static final String FILE = ".flint-store.json";
	/** The file layout this runtime reads. */
	static final int SCHEMA = 1;
	static final int MAX_BYTES = 16 * 1024;

	/** What a usable file says. */
	public record Contents(StoreEndpoint endpoint, long equipRevision) {
	}

	private final Path file;
	private long modified = Long.MIN_VALUE;
	private long size = -2;
	private @Nullable Contents contents;
	private boolean checked;

	/** The handoff in {@code gameDir} (the profile's folder: Minecraft's {@code --gameDir}). */
	public StoreHandoff(Path gameDir) {
		this.file = gameDir.resolve(FILE);
	}

	/**
	 * Reads the file again if it changed since the last call (the first call always reads it). True when it changed: then
	 * {@link #endpoint} and {@link #equipRevision} may say something new.
	 */
	public boolean poll() {
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
			return false;
		}
		Contents now;
		if (s < 0) {
			now = null;
		} else if (s > MAX_BYTES) {
			FlintLog.LOGGER.warn("Flint Store: {} is too large to be the launcher's handoff; using {}", FILE, StoreEndpoint.DEFAULT_BASE);
			now = null;
		} else {
			String text;
			try {
				text = Files.readString(file, StandardCharsets.UTF_8);
			} catch (IOException | RuntimeException e) {
				// Being replaced (or briefly locked): what was read before stands, and the file is read again next time.
				checked = false;
				return false;
			}
			now = parse(text);
			if (now == null) {
				FlintLog.LOGGER.warn("Flint Store: {} isn't a handoff this runtime reads (schema {}, contract v{}); using {}", FILE, SCHEMA,
					StoreEndpoint.CONTRACT_VERSION, StoreEndpoint.DEFAULT_BASE);
			}
		}
		checked = true;
		modified = m;
		size = s;
		Contents before = contents;
		contents = now;
		return !java.util.Objects.equals(before, now);
	}

	/** What {@code json} says, or null when this runtime can't use it (see the class comment). */
	static @Nullable Contents parse(String json) {
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
		Long contract = JsonFields.integer(root, "contractVersion");
		if (schema == null || schema != SCHEMA || contract == null || contract != StoreEndpoint.CONTRACT_VERSION) {
			return null;
		}
		StoreEndpoint endpoint = StoreEndpoint.parse(JsonFields.string(root, "storeBaseUrl"), "launcher " + FILE);
		if (endpoint == null) {
			return null;
		}
		if (root.has("publicCosmeticsUrl")) {
			String lookup = JsonFields.string(root, "publicCosmeticsUrl");
			if (lookup == null || !lookup.trim().toLowerCase(Locale.ROOT).equals(endpoint.publicCosmeticsUrl().toLowerCase(Locale.ROOT))) {
				return null;
			}
		}
		long revision = 0;
		if (root.has("equipRevision")) {
			Long r = JsonFields.integer(root, "equipRevision");
			if (r == null || r < 0) {
				return null;
			}
			revision = r;
		}
		return new Contents(endpoint, revision);
	}

	/** The Store to use: {@value StoreEndpoint#PROPERTY} when set, else the file's when usable, else the built-in one. */
	public StoreEndpoint endpoint() {
		StoreEndpoint property = StoreEndpoint.fromProperty();
		if (property != null) {
			return property;
		}
		Contents c = contents;
		return c != null ? c.endpoint() : StoreEndpoint.defaults();
	}

	/** The launcher's last equip revision (0 without a usable file). */
	public long equipRevision() {
		Contents c = contents;
		return c == null ? 0 : c.equipRevision();
	}

	public Path file() {
		return file;
	}
}

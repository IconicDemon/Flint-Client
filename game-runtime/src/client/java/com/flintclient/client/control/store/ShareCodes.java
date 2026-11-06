package com.flintclient.client.control.store;

import com.flintclient.client.control.model.Layout;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * Profile codes: a profile as one line of text to send to a friend ({@code FLINT-…}), which their Flint turns back into
 * the same profile. A code holds only what differs from a new profile (the rest is a new profile's anyway), compressed;
 * it starts with a few random bytes, so sharing the same profile twice gives two different codes. Nothing is uploaded:
 * the code is the profile. Share uses a 6-character code instead when Flint's servers are there ({@link ShortCodes},
 * {@link ProfileShareClient}, which keep the same {@link #payloadJson}); a long code is the fallback, and always imports.
 * Version-neutral.
 */
public final class ShareCodes {
	public static final String PREFIX = "FLINT-";
	/** The longest code read (a profile with every setting changed is well under it). */
	public static final int MAX_CODE_CHARS = 100_000;
	/** The most a code may grow to when unpacked. */
	private static final int MAX_JSON_BYTES = 2_000_000;
	private static final int SALT = 3;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final SecureRandom RANDOM = new SecureRandom();

	private ShareCodes() {
	}

	/** {@code layout} as a code. */
	public static String encode(Layout layout) {
		return pack(GSON.toJson(payload(layout)));
	}

	/**
	 * What a code holds for {@code layout}: what differs from a new profile, with its name and schema, as JSON. This is
	 * also what a short code's server keeps ({@link ProfileShareClient}); {@link LayoutStore#importText} reads it back.
	 */
	public static String payloadJson(Layout layout) {
		return GSON.toJson(payload(layout));
	}

	static JsonObject payload(Layout layout) {
		JsonObject full = LayoutCodec.toJson(layout);
		full.remove("id");
		JsonObject fresh = LayoutCodec.toJson(new Layout("fresh", layout.name()));
		JsonObject changed = difference(full, fresh);
		// The reader needs these to know what it is reading.
		changed.add(JsonStore.SCHEMA_KEY, full.get(JsonStore.SCHEMA_KEY));
		changed.addProperty("name", layout.name());
		if (!changed.has("modules")) {
			changed.add("modules", new JsonObject());
		}
		// A module the code lists is read as off unless it says it is on, so each one keeps its on or off.
		JsonObject modules = changed.getAsJsonObject("modules");
		JsonObject allModules = full.getAsJsonObject("modules");
		for (Map.Entry<String, JsonElement> e : modules.entrySet()) {
			JsonElement source = allModules == null ? null : allModules.get(e.getKey());
			if (e.getValue().isJsonObject() && source != null && source.isJsonObject() && source.getAsJsonObject().has("enabled")) {
				e.getValue().getAsJsonObject().add("enabled", source.getAsJsonObject().get("enabled"));
			}
		}
		return changed;
	}

	/** {@code text} (a profile's JSON) compressed, behind a few random bytes, as a code. */
	private static String pack(String text) {
		byte[] json = text.getBytes(StandardCharsets.UTF_8);
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
		deflater.setInput(json);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] salt = new byte[SALT];
		RANDOM.nextBytes(salt);
		out.writeBytes(salt);
		byte[] buffer = new byte[4096];
		while (!deflater.finished()) {
			out.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
	}

	/**
	 * The profile JSON a code holds (for {@link LayoutStore#importText}), or null when the text isn't a Flint profile code.
	 * Spaces and line breaks (from a chat or a message) are ignored; the prefix may be missing.
	 */
	public static @Nullable String decode(@Nullable String code) {
		if (code == null) {
			return null;
		}
		String text = code.replaceAll("\\s+", "");
		if (text.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
			text = text.substring(PREFIX.length());
		}
		if (text.isEmpty() || text.length() > MAX_CODE_CHARS) {
			return null;
		}
		byte[] packed;
		try {
			packed = Base64.getUrlDecoder().decode(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (packed.length <= SALT) {
			return null;
		}
		Inflater inflater = new Inflater(true);
		inflater.setInput(packed, SALT, packed.length - SALT);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try {
			while (!inflater.finished()) {
				int n = inflater.inflate(buffer);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					return null;
				}
				out.write(buffer, 0, n);
				if (out.size() > MAX_JSON_BYTES) {
					return null;
				}
			}
		} catch (DataFormatException e) {
			return null;
		} finally {
			inflater.end();
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	/** Whether {@code text} looks like a code (rather than a profile's JSON). */
	public static boolean looksLikeCode(@Nullable String text) {
		return text != null && text.strip().regionMatches(true, 0, PREFIX, 0, PREFIX.length());
	}

	/** What of {@code value} differs from {@code base}: objects compared key by key, anything else whole. */
	static JsonObject difference(JsonObject value, JsonObject base) {
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> e : value.entrySet()) {
			JsonElement old = base.get(e.getKey());
			JsonElement now = e.getValue();
			if (old == null || !old.equals(now)) {
				if (now.isJsonObject() && old != null && old.isJsonObject()) {
					JsonObject inner = difference(now.getAsJsonObject(), old.getAsJsonObject());
					if (!inner.isEmpty()) {
						out.add(e.getKey(), inner);
					}
				} else {
					out.add(e.getKey(), now);
				}
			}
		}
		return out;
	}
}

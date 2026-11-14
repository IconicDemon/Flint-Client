package com.flintclient.client.cosmetics;

import com.flintclient.client.control.store.StoreEndpoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A 3D cosmetic as the Store describes it (the cosmetics contract, website's answer): its kind, and its files, each to download
 * from an address {@link StoreEndpoint#allowsAsset} allows and to check against the Store's SHA-256 and byte count. The
 * Store's {@code asset.model}:
 *
 * <pre>
 * {geometry: {url, sha256, bytes}, texture: {url, sha256, bytes, width, height}, animation: {…} | null,
 *  texture_frames: int | null, frame_ms: int | int[] | null, attach: {anchor, side}, hides_cape: bool}
 * </pre>
 *
 * An emote has only {@code animation}; a texture-only shield has no geometry. Everything is untrusted: an entry that breaks the
 * contract's limits is null ({@link #read}), never part of one. Pure Java.
 */
public record ModelItem(String id, CosmeticKind kind, @Nullable FileRef geometry, @Nullable FileRef texture, @Nullable FileRef animation,
	int frames, int[] frameMs, int anchor, boolean mirror, boolean hidesCape) {
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern ID = Pattern.compile("[0-9a-zA-Z_.:-]{1,80}");

	/**
	 * One file: where it is (a Store address, or a local file for dev items), its SHA-256 and size; {@code cdn}: the same file on
	 * Flint's CDN ({@code cdn_url}), tried first when the endpoint allows its host, else null.
	 */
	public record FileRef(URI url, String sha256, int bytes, @Nullable URI cdn) {
		public FileRef(URI url, String sha256, int bytes) {
			this(url, sha256, bytes, null);
		}
	}

	/** What the model is drawn from: the same files, side and frames make one loaded model, whoever wears it. */
	public String key() {
		return (geometry == null ? "-" : geometry.sha256()) + "." + (texture == null ? "-" : texture.sha256()) + "."
			+ (animation == null ? "-" : animation.sha256()) + "." + frames + (mirror ? ".l" : ".r") + "." + kind.id;
	}

	/** How long frame {@code i} shows, in milliseconds. */
	public int frameMs(int i) {
		return frameMs.length == 0 ? 100 : frameMs[Math.min(i, frameMs.length - 1)];
	}

	/** One loop of the texture's frames, in milliseconds. */
	public long cycleMs() {
		long total = 0;
		for (int i = 0; i < frames; i++) {
			total += frameMs(i);
		}
		return total;
	}

	/**
	 * A worn slot's entry of the lookup ({@code {cosmetic_id, kind, asset: {…, model: {…}}}}) as an item of kind {@code expected}
	 * (null: the entry's own), or null when it isn't one the game may draw.
	 */
	public static @Nullable ModelItem read(@Nullable JsonObject entry, @Nullable CosmeticKind expected, StoreEndpoint endpoint) {
		if (entry == null) {
			return null;
		}
		CosmeticKind kind = CosmeticKind.of(Json.string(entry, "kind"));
		if (kind == null || expected != null && kind != expected) {
			return null;
		}
		String id = Json.string(entry, "cosmetic_id");
		if (id == null) {
			id = Json.string(entry, "id");
		}
		JsonObject asset = Json.object(entry, "asset");
		JsonObject model = asset == null ? Json.object(entry, "model") : Json.object(asset, "model");
		if (id == null || !ID.matcher(id).matches() || model == null) {
			return null;
		}
		if (addressed(model)) {
			return model(id, kind, model, endpoint);
		}
		// As the Flint app describes its items: no addresses; the files come through it.
		return linked(id, kind, model);
	}

	/** Whether a model names its files' addresses (the Store's own answer) rather than leaving them to the Flint app. */
	private static boolean addressed(JsonObject model) {
		for (String part : new String[] {"geometry", "texture", "animation"}) {
			JsonObject f = Json.object(model, part);
			if (f != null && f.has("url")) {
				return true;
			}
		}
		return false;
	}

	/** The address of a file the Flint app hands over ({@code modelFile}): {@code flint-link:/<id>/<part>}. */
	public static final String LINK = "flint-link";

	/**
	 * An item as the Flint app describes it to the game (its "model": sizes, frames, anchor; no addresses or hashes): its
	 * files come through the app ({@code SocialLink.modelFile}), which checked them against the Store's SHA-256 and size; the
	 * game checks the size again. Null when it isn't one the game may draw.
	 */
	public static @Nullable ModelItem linked(String id, CosmeticKind kind, JsonObject model) {
		if (!ID.matcher(id).matches()) {
			return null;
		}
		String key = id.toLowerCase(Locale.ROOT);
		FileRef geometry = linkFile(key, "geometry", Json.object(model, "geometry"), CosmeticLimits.MAX_GEOMETRY_BYTES);
		FileRef texture = linkFile(key, "texture", Json.object(model, "texture"), CosmeticLimits.MAX_TEXTURE_BYTES);
		FileRef animation = linkFile(key, "animation", Json.object(model, "animation"), CosmeticLimits.MAX_ANIMATION_BYTES);
		if (kind == CosmeticKind.EMOTE) {
			return animation == null ? null : new ModelItem(key, kind, null, null, animation, 1, new int[0], CosmeticModel.BODY, false, false);
		}
		if (texture == null || geometry == null && kind != CosmeticKind.SHIELD) {
			return null;
		}
		Long frames = Json.integer(model, "textureFrames", 1, CosmeticLimits.MAX_FRAMES);
		int n = frames == null ? 1 : frames.intValue();
		int[] ms = frameMs(model.get("frameMs"), n);
		if (ms == null) {
			// The app sends one value per frame (or none): an empty list is the default.
			ms = new int[] {100};
		}
		JsonObject attach = Json.object(model, "attach");
		int anchor = CosmeticKind.anchor(Json.string(attach, "anchor"));
		boolean mirror = kind == CosmeticKind.PET && "left".equals(Json.string(attach, "side"));
		return new ModelItem(key, kind, geometry, texture, animation, n, ms, anchor < 0 ? kind.defaultAnchor : anchor, mirror,
			kind == CosmeticKind.WINGS && Json.bool(model, "hidesCape"));
	}

	/** A file the app holds: its size from the app; its "hash" a name of its own (the app checked the real one). */
	private static @Nullable FileRef linkFile(String id, String part, @Nullable JsonObject f, int maxBytes) {
		Long bytes = Json.integer(f, "bytes", 1, maxBytes);
		if (f == null || bytes == null) {
			return null;
		}
		return new FileRef(java.net.URI.create(LINK + ":/" + id + "/" + part), "link." + id + "." + part, bytes.intValue());
	}

	/** An {@code asset.model} object for item {@code id} of {@code kind}, or null. */
	public static @Nullable ModelItem model(String id, CosmeticKind kind, JsonObject model, StoreEndpoint endpoint) {
		FileRef geometry = file(Json.object(model, "geometry"), endpoint, CosmeticLimits.MAX_GEOMETRY_BYTES);
		FileRef texture = file(Json.object(model, "texture"), endpoint, CosmeticLimits.MAX_TEXTURE_BYTES);
		FileRef animation = file(Json.object(model, "animation"), endpoint, CosmeticLimits.MAX_ANIMATION_BYTES);
		if (kind == CosmeticKind.EMOTE) {
			if (animation == null) {
				return null;
			}
			return new ModelItem(id, kind, null, null, animation, 1, new int[0], CosmeticModel.BODY, false, false);
		}
		// A shield may be its texture alone (on Minecraft's shield); everything else needs geometry and a texture.
		if (texture == null || geometry == null && kind != CosmeticKind.SHIELD) {
			return null;
		}
		Long frames = Json.integer(model, "texture_frames", 1, CosmeticLimits.MAX_FRAMES);
		JsonElement f = model.get("texture_frames");
		if (frames == null && f != null && !f.isJsonNull()) {
			return null;
		}
		int n = frames == null ? 1 : frames.intValue();
		int[] ms = frameMs(model.get("frame_ms"), n);
		if (ms == null) {
			return null;
		}
		JsonObject attach = Json.object(model, "attach");
		int anchor = CosmeticKind.anchor(Json.string(attach, "anchor"));
		String side = Json.string(attach, "side");
		boolean mirror = kind == CosmeticKind.PET && "left".equals(side);
		return new ModelItem(id.toLowerCase(Locale.ROOT), kind, geometry, texture, animation, n, ms, anchor < 0 ? kind.defaultAnchor : anchor,
			mirror, kind == CosmeticKind.WINGS && Json.bool(model, "hides_cape"));
	}

	/** {@code frame_ms}: one value or one per frame, each at least {@link CosmeticLimits#MIN_FRAME_MS}; null when it breaks that. */
	static int @Nullable [] frameMs(@Nullable JsonElement e, int frames) {
		if (e == null || e.isJsonNull()) {
			return new int[] {100};
		}
		if (e instanceof JsonPrimitive p && p.isNumber()) {
			double v = p.getAsDouble();
			return v >= CosmeticLimits.MIN_FRAME_MS && v <= 10_000 ? new int[] {(int) v} : null;
		}
		if (!e.isJsonArray()) {
			return null;
		}
		JsonArray a = e.getAsJsonArray();
		if (a.size() == 0 || a.size() > CosmeticLimits.MAX_FRAMES || a.size() != 1 && a.size() != frames) {
			return null;
		}
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			if (!(a.get(i) instanceof JsonPrimitive p) || !p.isNumber()) {
				return null;
			}
			double v = p.getAsDouble();
			if (v < CosmeticLimits.MIN_FRAME_MS || v > 10_000) {
				return null;
			}
			out[i] = (int) v;
		}
		return out;
	}

	/** A file entry, or null when it is missing or any part of it is wrong. */
	static @Nullable FileRef file(@Nullable JsonObject f, StoreEndpoint endpoint, int maxBytes) {
		if (f == null) {
			return null;
		}
		String url = Json.string(f, "url");
		String sha = Json.string(f, "sha256");
		Long bytes = Json.integer(f, "bytes", 1, maxBytes);
		if (url == null || url.length() > 2048 || sha == null || bytes == null) {
			return null;
		}
		String hash = sha.toLowerCase(Locale.ROOT);
		if (!SHA256.matcher(hash).matches()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return null;
		}
		if (!endpoint.allowsAsset(uri)) {
			return null;
		}
		URI cdn = null;
		String cdnUrl = Json.string(f, "cdn_url");
		if (cdnUrl != null && cdnUrl.length() <= 2048) {
			try {
				URI c = new URI(cdnUrl);
				cdn = endpoint.allowsAsset(c) ? c : null;
			} catch (URISyntaxException e) {
				// The Store's own address only.
			}
		}
		return new FileRef(uri, hash, bytes.intValue(), cdn);
	}
}

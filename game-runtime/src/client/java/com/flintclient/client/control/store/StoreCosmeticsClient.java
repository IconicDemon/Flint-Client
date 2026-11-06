package com.flintclient.client.control.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The Flint Store's public cosmetics lookup ({@code GET /api/v1/players/cosmetics/?uuids=a,b}, no token; Store
 * src/lib/server/api.ts {@code publicCosmetics}) and the asset downloads it points to. A lookup answers
 * {@code {"players": {"<uuid>": {"<slot>": {"cosmetic_id", "kind", "asset": {"id", "version", "path", "sha256", "width",
 * "height", "url", "animation"}}}}}} for the players who wear something; a player left out wears nothing. Only the {@code cape}
 * slot is read. Everything in an answer is untrusted and checked: a player Flint didn't ask about, an asset with a malformed id
 * or hash, a size that isn't a cape's, or an address {@link StoreEndpoint#allowsAsset} refuses, is left out. An animated cape's
 * {@code animation} ({@link CapeAnimation}) that doesn't check out is dropped, never the cape: it shows its poster ({@code url}).
 *
 * <p>Requests run off the game's thread with short timeouts, never follow redirects and read at most {@link #MAX_LOOKUP_BYTES}
 * of a lookup, {@link #MAX_ASSET_BYTES} of a poster and {@link CapeAnimation#MAX_STRIP_BYTES} of an animation strip. Sends no
 * credentials and no cookies. Pure Java; version-neutral.
 */
public final class StoreCosmeticsClient {
	/** The most players one lookup asks about (the Store reads no more). */
	public static final int MAX_BATCH = 100;
	/**
	 * The most a lookup's answer may take: {@link #MAX_BATCH} players who all wear a 120-frame HD animated cape (40 strips, about
	 * 15 KB of manifest each with the Store's addresses) come to about 1.6 MB; 4 MiB leaves room for longer asset addresses.
	 */
	public static final int MAX_LOOKUP_BYTES = 4 * 1024 * 1024;
	/** The most a cape texture (an animated cape's poster too) may take: the Store's own limit for a poster (2 MiB). */
	public static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
	/** Cape textures are Minecraft's 64×32 at one of these whole multiples (Store/Creator src/lib/cosmetics.ts CAPE_SCALES). */
	static final int[] CAPE_SCALES = {1, 2, 4, 8, 16};
	public static final int MAX_CAPE_WIDTH = 64 * 16;
	public static final int MAX_CAPE_HEIGHT = 32 * 16;
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern MAX_AGE = Pattern.compile("(?:^|[,\\s])max-age\\s*=\\s*\"?(\\d{1,9})\"?");

	/** How a lookup ended. */
	public enum Status {
		OK,
		/** The Store couldn't be reached (no connection, a timeout). */
		OFFLINE,
		/** The Store is limiting requests or unavailable for now (429, 5xx). */
		BUSY,
		/** An answer Flint doesn't understand (malformed JSON, an unexpected status). */
		FAILED
	}

	/** An HTTP answer: status, body (at most the cap asked for) and the two headers Flint reads. */
	public record Response(int status, byte[] body, @Nullable String cacheControl, @Nullable String retryAfter) {
	}

	/**
	 * Sends one GET (no credentials, no redirects) and reads at most {@code maxBytes}; {@code fresh}: past any cache on the way
	 * ({@code Cache-Control: no-cache}). The real one is {@link #http()}.
	 */
	public interface Transport {
		CompletableFuture<Response> get(URI uri, String accept, int maxBytes, boolean fresh);
	}

	/**
	 * A cosmetic's texture as the Store describes it: download {@code url}, check {@code sha256}; cache by id and version. An
	 * animated cape has its {@code animation} (null: a static cape); {@code url} is then its poster, frame 0.
	 */
	public record Asset(String id, int version, URI url, String sha256, int width, int height, @Nullable CapeAnimation animation) {
		public Asset(String id, int version, URI url, String sha256, int width, int height) {
			this(id, version, url, sha256, width, height, null);
		}

		@Override
		public String toString() {
			return "Asset[" + id + " v" + version + ", " + width + "x" + height + ", " + sha256.substring(0, 12) + "…"
				+ (animation == null ? "" : ", " + animation) + "]";
		}
	}

	/**
	 * A lookup's outcome: for {@link Status#OK}, the cape each asked-about player wears (absent: none); how long the answer may
	 * be kept ({@code maxAgeSeconds}, -1 when the Store didn't say) and, for {@link Status#BUSY}, how long to wait
	 * ({@code retryAfterSeconds}, -1 when it didn't say).
	 */
	public record Lookup(Status status, Map<UUID, Asset> capes, long maxAgeSeconds, long retryAfterSeconds) {
		static Lookup of(Status status, long retryAfter) {
			return new Lookup(status, Map.of(), -1, retryAfter);
		}
	}

	/** An asset that must not be used (refused address, too big, not there): not worth retrying soon. */
	public static final class Refused extends Exception {
		public Refused(String reason) {
			super(reason, null, false, false);
		}
	}

	private final StoreEndpoint endpoint;
	private final Transport transport;

	public StoreCosmeticsClient(StoreEndpoint endpoint, Transport transport) {
		this.endpoint = endpoint;
		this.transport = transport;
	}

	public StoreEndpoint endpoint() {
		return endpoint;
	}

	/** What {@code players} (the first {@link #MAX_BATCH}) wear now. */
	public CompletableFuture<Lookup> lookup(List<UUID> players) {
		return lookup(players, false);
	}

	/** {@link #lookup(List)}; {@code fresh}: asked past any cache (the launcher said something was equipped). */
	public CompletableFuture<Lookup> lookup(List<UUID> players, boolean fresh) {
		Set<UUID> asked = new LinkedHashSet<>();
		for (UUID player : players) {
			if (asked.size() == MAX_BATCH) {
				break;
			}
			asked.add(player);
		}
		if (asked.isEmpty()) {
			return CompletableFuture.completedFuture(new Lookup(Status.OK, Map.of(), -1, -1));
		}
		CompletableFuture<Response> answer;
		try {
			answer = transport.get(endpoint.cosmeticsLookup(asked), "application/json", MAX_LOOKUP_BYTES, fresh);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(Lookup.of(Status.FAILED, -1));
		}
		return answer.thenApply(r -> read(r, asked, endpoint)).exceptionally(error -> Lookup.of(offline(error) ? Status.OFFLINE : Status.FAILED, -1));
	}

	/**
	 * The bytes at {@code asset}'s address (not yet checked against its hash: the caller does). Fails with {@link Refused} for an
	 * address the endpoint doesn't allow (nothing is sent), an answer that isn't 200, or one larger than
	 * {@link #MAX_ASSET_BYTES}; with an {@link IOException} when the host can't be reached.
	 */
	public CompletableFuture<byte[]> download(Asset asset) {
		return download(asset.url(), MAX_ASSET_BYTES);
	}

	/**
	 * An animation strip's bytes, as {@link #download(Asset)}: at most the size the Store gave for it (the strip's exact size,
	 * itself at most {@link CapeAnimation#MAX_STRIP_BYTES}), so a cape's strips never take more than the manifest said.
	 */
	public CompletableFuture<byte[]> download(CapeAnimation.Strip strip) {
		return download(strip.url(), Math.min(strip.bytes(), CapeAnimation.MAX_STRIP_BYTES));
	}

	private CompletableFuture<byte[]> download(URI url, int maxBytes) {
		if (!endpoint.allowsAsset(url)) {
			return CompletableFuture.failedFuture(new Refused("address not allowed: " + url.getHost()));
		}
		CompletableFuture<Response> answer;
		try {
			answer = transport.get(url, "image/png", maxBytes, false);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return answer.thenCompose(r -> {
			if (r.status() == 200 && r.body().length <= maxBytes) {
				return CompletableFuture.completedFuture(r.body());
			}
			if (r.status() == 429 || r.status() >= 500) {
				return CompletableFuture.failedFuture(new IOException("asset host answered " + r.status()));
			}
			return CompletableFuture.failedFuture(new Refused("asset host answered " + r.status()));
		});
	}

	/** A lookup's answer for the players {@code asked}. */
	static Lookup read(Response response, Set<UUID> asked, StoreEndpoint endpoint) {
		int code = response.status();
		if (code == 429 || code >= 500) {
			return Lookup.of(Status.BUSY, retryAfter(response.retryAfter()));
		}
		if (code != 200 || response.body().length > MAX_LOOKUP_BYTES) {
			return Lookup.of(Status.FAILED, -1);
		}
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return Lookup.of(Status.FAILED, -1);
			}
			root = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			return Lookup.of(Status.FAILED, -1);
		}
		JsonElement players = root.get("players");
		if (players == null || !players.isJsonObject()) {
			return Lookup.of(Status.FAILED, -1);
		}
		Map<UUID, Asset> capes = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : players.getAsJsonObject().entrySet()) {
			UUID player = uuid(entry.getKey());
			if (player == null || !asked.contains(player) || !entry.getValue().isJsonObject()) {
				continue;
			}
			JsonObject cape = JsonFields.object(entry.getValue().getAsJsonObject(), "cape");
			String kind = JsonFields.string(cape, "kind");
			if (cape == null || kind != null && !kind.equals("cape")) {
				continue;
			}
			Asset asset = asset(JsonFields.object(cape, "asset"), endpoint);
			if (asset != null) {
				capes.put(player, asset);
			}
		}
		return new Lookup(Status.OK, Collections.unmodifiableMap(capes), maxAge(response.cacheControl()), -1);
	}

	/** An asset entry, or null when any part of it is missing or not what a cape texture can be. */
	static @Nullable Asset asset(@Nullable JsonObject asset, StoreEndpoint endpoint) {
		if (asset == null) {
			return null;
		}
		String id = JsonFields.string(asset, "id");
		Long version = JsonFields.integer(asset, "version");
		String sha256 = JsonFields.string(asset, "sha256");
		Long width = JsonFields.integer(asset, "width");
		Long height = JsonFields.integer(asset, "height");
		String url = JsonFields.string(asset, "url");
		if (id == null || uuid(id) == null || version == null || version < 1 || version > 1_000_000 || sha256 == null || width == null
			|| height == null || url == null || url.length() > 2048) {
			return null;
		}
		String hash = sha256.toLowerCase(Locale.ROOT);
		if (!SHA256.matcher(hash).matches() || !capeSize(width, height)) {
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
		CapeAnimation animation = CapeAnimation.parse(asset.get("animation"), endpoint);
		if (animation != null && !posterFits(animation, width, height)) {
			// A poster that isn't frame 0's texture: shown as it is, without the animation.
			animation = null;
		}
		return new Asset(id.toLowerCase(Locale.ROOT), version.intValue(), uri, hash, width.intValue(), height.intValue(), animation);
	}

	/** The scale of an animated cape's poster: the animation's, at most 16 (an HD frame is halved). */
	public static int posterScale(CapeAnimation animation) {
		return Math.min(animation.scale(), 16);
	}

	/** Whether a {@code width × height} poster is {@code animation}'s: 64×32 at {@link #posterScale}. */
	static boolean posterFits(CapeAnimation animation, long width, long height) {
		int s = posterScale(animation);
		return width == 64L * s && height == 32L * s;
	}

	/** Whether {@code width × height} is a cape texture's size: 64×32 at a whole multiple the Store allows. */
	public static boolean capeSize(long width, long height) {
		for (int scale : CAPE_SCALES) {
			if (width == 64L * scale && height == 32L * scale) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable UUID uuid(String text) {
		String t = text.toLowerCase(Locale.ROOT);
		if (!UUID_TEXT.matcher(t).matches()) {
			return null;
		}
		try {
			return UUID.fromString(t);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** {@code max-age} of a Cache-Control header in seconds; 0 for {@code no-store}/{@code no-cache}; -1 when it says neither. */
	static long maxAge(@Nullable String cacheControl) {
		if (cacheControl == null) {
			return -1;
		}
		String c = cacheControl.toLowerCase(Locale.ROOT);
		if (c.contains("no-store") || c.contains("no-cache")) {
			return 0;
		}
		Matcher m = MAX_AGE.matcher(c);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	/** A Retry-After header given in seconds (an HTTP date isn't read), or -1. */
	static long retryAfter(@Nullable String retryAfter) {
		if (retryAfter == null || !retryAfter.trim().matches("\\d{1,9}")) {
			return -1;
		}
		return Long.parseLong(retryAfter.trim());
	}

	/** Whether a request failed for want of a connection (or a timeout), rather than an answer. */
	public static boolean offline(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof IOException;
	}

	// ---------- the real transport ----------

	/** Java's HTTP client: short timeouts, no redirects, no cookies, answers cut at the cap asked for. */
	public static Transport http() {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
		return (uri, accept, maxBytes, fresh) -> {
			HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", accept)
				.header("User-Agent", "FlintClient-Game").GET();
			if (fresh) {
				builder.header("Cache-Control", "no-cache");
			}
			HttpRequest request = builder.build();
			return client.sendAsync(request, info -> new CappedBytes(maxBytes)).thenApply(r -> new Response(r.statusCode(), r.body(),
				r.headers().firstValue("cache-control").orElse(null), r.headers().firstValue("retry-after").orElse(null)));
		};
	}

	/** A body that fails (and stops the download) once it passes {@code max} bytes. */
	static final class CappedBytes implements HttpResponse.BodySubscriber<byte[]> {
		private final int max;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> result = new CompletableFuture<>();
		private Flow.@Nullable Subscription subscription;

		CappedBytes(int max) {
			this.max = max;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			subscription = s;
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (result.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				if (bytes.size() + (long) item.remaining() > max) {
					if (subscription != null) {
						subscription.cancel();
					}
					// Not an IOException: an answer this big is refused, not "offline".
					result.completeExceptionally(new Refused("answer larger than " + max + " bytes"));
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				bytes.write(chunk, 0, chunk.length);
			}
		}

		@Override
		public void onError(Throwable error) {
			result.completeExceptionally(error);
		}

		@Override
		public void onComplete() {
			result.complete(bytes.toByteArray());
		}
	}
}

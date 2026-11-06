package com.flintclient.client.control.store;

import com.flintclient.client.FlintLog;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where the Flint Store is, for the cosmetics the game draws ({@link StoreCosmeticsClient}), and which addresses its assets may
 * come from. The game needs no Store token: it reads only the Store's public lookup ({@code GET
 * /api/v1/players/cosmetics/?uuids=…}, Store docs/LAUNCHER-API.md).
 *
 * <p>The address comes from, in order: the {@value #PROPERTY} system property (development and QA), the launcher's handoff
 * ({@link StoreHandoff}), else {@value #DEFAULT_BASE}. A base is an {@code https} origin (no path, query or credentials), or
 * {@code http} on {@code localhost} or {@code 127.0.0.1} for a Store on this computer. Neither can grant anything: what a
 * player wears is only what that Store answers for them.
 *
 * <p>Assets are downloaded only from the Store's own origin, or over {@code https} from a Supabase project's public
 * {@code cosmetic-assets} bucket (where the Store keeps real textures: Store src/lib/server/config.ts {@code assetUrl}); never
 * with credentials, a query, or a {@code .}/{@code ..} path segment. Pure Java; version-neutral.
 */
public final class StoreEndpoint {
	public static final String DEFAULT_BASE = "https://store.flintclient.com";
	/** The Store API contract this runtime speaks ({@code /api/v1/}). */
	public static final int CONTRACT_VERSION = 1;
	/** A Store address for development or QA ({@code -Dflint.store.baseUrl=http://127.0.0.1:8787}). */
	public static final String PROPERTY = "flint.store.baseUrl";
	public static final String LOOKUP_PATH = "/api/v1/players/cosmetics/";
	static final String SUPABASE_ASSETS = "/storage/v1/object/public/cosmetic-assets/";
	private static final Pattern SUPABASE_HOST = Pattern.compile("[a-z0-9]{20}\\.supabase\\.co");
	private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");

	private final String scheme;
	private final String host;
	private final int port;
	private final String source;

	private StoreEndpoint(String scheme, String host, int port, String source) {
		this.scheme = scheme;
		this.host = host;
		this.port = port;
		this.source = source;
	}

	public static StoreEndpoint defaults() {
		StoreEndpoint endpoint = parse(DEFAULT_BASE, "built in");
		if (endpoint == null) {
			throw new IllegalStateException("the default Store address is invalid");
		}
		return endpoint;
	}

	/** {@code url} as a Store base ({@code source} says where it came from, for the log), or null when it can't be one. */
	public static @Nullable StoreEndpoint parse(@Nullable String url, String source) {
		if (url == null || url.isBlank() || url.length() > 200) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		if (uri.isOpaque() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
			|| !(path.isEmpty() || path.equals("/")) || host.isEmpty()) {
			return null;
		}
		boolean loopback = loopback(host);
		if (!(scheme.equals("https") && (HOST.matcher(host).matches() || loopback) || scheme.equals("http") && loopback)) {
			return null;
		}
		int port = uri.getPort();
		if (port == 0 || port > 65535) {
			return null;
		}
		return new StoreEndpoint(scheme, host, port == -1 ? defaultPort(scheme) : port, source);
	}

	/** The {@value #PROPERTY} system property's Store, or null when it isn't set (or is unusable: logged). */
	public static @Nullable StoreEndpoint fromProperty() {
		String property = System.getProperty(PROPERTY);
		if (property == null) {
			return null;
		}
		StoreEndpoint endpoint = parse(property, "-D" + PROPERTY);
		if (endpoint == null) {
			FlintLog.LOGGER.warn("Flint Store: -D{} isn't an https origin (or http on localhost); it's ignored", PROPERTY);
		}
		return endpoint;
	}

	/** The public lookup of {@code players}' equipped cosmetics (lowercase, comma-separated, in order). */
	public URI cosmeticsLookup(Collection<UUID> players) {
		StringJoiner ids = new StringJoiner(",");
		for (UUID player : players) {
			ids.add(player.toString().toLowerCase(Locale.ROOT));
		}
		return URI.create(publicCosmeticsUrl() + "?uuids=" + ids);
	}

	/** The public lookup's address, without players: {@code <origin>/api/v1/players/cosmetics/}. */
	public String publicCosmeticsUrl() {
		return origin() + LOOKUP_PATH;
	}

	/** Whether an asset may be downloaded from {@code uri} (see the class comment). */
	public boolean allowsAsset(@Nullable URI uri) {
		if (uri == null || uri.isOpaque() || !uri.isAbsolute() || uri.getRawUserInfo() != null || uri.getRawQuery() != null
			|| uri.getRawFragment() != null || uri.getHost() == null || uri.getRawPath() == null || !safePath(uri.getRawPath())) {
			return false;
		}
		String s = uri.getScheme().toLowerCase(Locale.ROOT);
		String h = uri.getHost().toLowerCase(Locale.ROOT);
		int p = uri.getPort() == -1 ? defaultPort(s) : uri.getPort();
		if (s.equals(scheme) && h.equals(host) && p == port) {
			return true;
		}
		return s.equals("https") && p == 443 && SUPABASE_HOST.matcher(h).matches() && uri.getRawPath().startsWith(SUPABASE_ASSETS)
			&& uri.getRawPath().length() > SUPABASE_ASSETS.length();
	}

	/** No empty, {@code .} or {@code ..} segment (also percent-encoded), no backslash. */
	private static boolean safePath(String rawPath) {
		if (!rawPath.startsWith("/") || rawPath.contains("\\") || rawPath.toLowerCase(Locale.ROOT).contains("%2e")
			|| rawPath.toLowerCase(Locale.ROOT).contains("%2f") || rawPath.contains("//")) {
			return false;
		}
		for (String segment : rawPath.substring(1).split("/", -1)) {
			if (segment.equals(".") || segment.equals("..")) {
				return false;
			}
		}
		return true;
	}

	private static boolean loopback(String host) {
		return host.equals("127.0.0.1") || host.equals("localhost");
	}

	private static int defaultPort(String scheme) {
		return scheme.equals("https") ? 443 : 80;
	}

	/** {@code scheme://host[:port]}, without a trailing slash. */
	public String origin() {
		return scheme + "://" + host + (port == defaultPort(scheme) ? "" : ":" + port);
	}

	/** A Store on this computer (development and QA: {@code localhost} or {@code 127.0.0.1}). */
	public boolean loopback() {
		return loopback(host);
	}

	/** The Store's host (for the log: never a path or query). */
	public String host() {
		return host;
	}

	/** Where the address came from: "built in", the launcher's handoff, or the system property. */
	public String source() {
		return source;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof StoreEndpoint e && e.origin().equals(origin());
	}

	@Override
	public int hashCode() {
		return origin().hashCode();
	}

	@Override
	public String toString() {
		return origin() + " (" + source + ")";
	}
}

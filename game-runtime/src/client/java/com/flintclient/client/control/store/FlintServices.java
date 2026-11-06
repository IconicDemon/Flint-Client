package com.flintclient.client.control.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Where Flint's online services are, as the build wrote it into the jar ({@code assets/flintclient/flint-services.json},
 * from the build's {@code FLINT_SUPABASE_URL} and {@code FLINT_SUPABASE_ANON_KEY}; never kept in the source). The key is
 * the project's publishable one, which is public by design: everything behind it is limited by the server. A build
 * without them says {@code "configured": false}, and Flint then works without the services (profiles are shared as long
 * codes). Version-neutral.
 *
 * @param baseUrl the project's address, without a trailing slash ({@code https://<ref>.supabase.co})
 * @param anonKey the publishable key
 */
public record FlintServices(String baseUrl, String anonKey) {
	public static final String RESOURCE = "/assets/flintclient/flint-services.json";
	private static final int MAX_FILE = 16 * 1024;
	private static volatile @Nullable FlintServices bundled;
	private static volatile boolean read;

	/** The services this jar was built with, or null when it was built without them (or the file is unusable). */
	public static @Nullable FlintServices bundled() {
		if (!read) {
			synchronized (FlintServices.class) {
				if (!read) {
					bundled = readBundled();
					read = true;
				}
			}
		}
		return bundled;
	}

	private static @Nullable FlintServices readBundled() {
		try (InputStream in = FlintServices.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				return null;
			}
			byte[] bytes = in.readNBytes(MAX_FILE + 1);
			return bytes.length > MAX_FILE ? null : parse(new String(bytes, StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The services {@code json} names, or null when it says they aren't configured or isn't usable. */
	public static @Nullable FlintServices parse(@Nullable String json) {
		if (json == null) {
			return null;
		}
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
		if (!Boolean.TRUE.equals(JsonFields.bool(root, "configured"))) {
			return null;
		}
		String url = JsonFields.string(root, "supabaseUrl");
		String key = JsonFields.string(root, "supabaseAnonKey");
		if (url == null || key == null) {
			return null;
		}
		url = url.strip();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		key = key.strip();
		return usableUrl(url) && usableKey(key) ? new FlintServices(url, key) : null;
	}

	/** An https address with a host and nothing after it (plain http only for this computer, for local testing). */
	static boolean usableUrl(String url) {
		try {
			URI uri = new URI(url);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost();
			if (host == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
				return false;
			}
			boolean local = host.equals("localhost") || host.equals("127.0.0.1");
			return scheme.equals("https") || scheme.equals("http") && local;
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	/** A key that can go in a header: printable ASCII, no spaces, a sane length; never a secret key. */
	static boolean usableKey(String key) {
		if (key.isEmpty() || key.length() > 4096 || key.startsWith("sb_secret_")) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			char ch = key.charAt(i);
			if (ch <= ' ' || ch >= 127) {
				return false;
			}
		}
		return true;
	}

	/** The address of database function {@code name} through the Data API. */
	public URI rpc(String name) {
		return URI.create(baseUrl + "/rest/v1/rpc/" + name);
	}

	/** Whether the services run on this computer (development and QA builds; a release's never do). */
	public boolean loopback() {
		try {
			String host = new URI(baseUrl).getHost();
			return host != null && (host.equals("localhost") || host.equals("127.0.0.1"));
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	@Override
	public String toString() {
		// Never print the key.
		return "FlintServices[" + baseUrl + "]";
	}
}

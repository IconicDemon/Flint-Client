package com.flintclient.legacy.shared;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserMigratedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 1.8.9's join with Mojang's session server ({@code session/minecraft/join}), sent by Flint instead of authlib 1.5.21.
 * Measured from the owner's PC on 2026-11-04, that endpoint answers 503 to about one request in seven, in bursts of a
 * second or two, and 503 then 429 once more than about fifteen came in a minute. authlib 1.5.21 reads such an answer's body
 * ({@code {"path": ...}}, no error in it) as a join that went through: the server then can't confirm the player and the
 * join ends in "Failed to verify username!". Here the status decides: 2xx joined; a refusal is thrown as authlib throws it
 * (an invalid session as {@link InvalidCredentialsException}, Mojang's message otherwise); 5xx, 429 or no answer is tried
 * again after 1, 2, 3.5 and 5 seconds (no new try more than {@link #BUDGET_MS} after the first), then reported as authlib
 * reports an unreachable server. The same request authlib sends, to the address authlib would use, through its proxy;
 * the access token goes to that address only and is never logged.
 */
public final class LegacyJoin {
	static final long[] WAITS_MS = {1_000, 2_000, 3_500, 5_000};
	static final int ATTEMPTS = WAITS_MS.length + 1;
	static final long BUDGET_MS = 16_000;
	static final int TIMEOUT_MS = 15_000;
	private static final int MAX_BODY = 64 * 1024;

	private LegacyJoin() {
	}

	/** One answer: its HTTP status and body. */
	record Answer(int status, String body) {
	}

	/** Sends one join request (a test answers instead). */
	@FunctionalInterface
	interface Transport {
		Answer post(String json) throws IOException;
	}

	@FunctionalInterface
	interface Pause {
		void sleep(long ms) throws InterruptedException;
	}

	@FunctionalInterface
	interface Clock {
		long nowMs();
	}

	/** Joins {@code serverId} as {@code profile} at {@code url} (authlib's own join address), through {@code proxy}. */
	public static void join(URL url, Proxy proxy, String accessToken, UUID profile, String serverId, Consumer<String> log) throws AuthenticationException {
		join(json -> post(url, proxy, json), accessToken, profile, serverId, log, Thread::sleep, System::currentTimeMillis);
	}

	static void join(Transport transport, String accessToken, UUID profile, String serverId, Consumer<String> log, Pause pause, Clock clock) throws AuthenticationException {
		JsonObject request = new JsonObject();
		request.addProperty("accessToken", accessToken);
		request.addProperty("selectedProfile", profile.toString().replace("-", ""));
		request.addProperty("serverId", serverId);
		String json = request.toString();
		long started = clock.nowMs();
		for (int attempt = 1; ; attempt++) {
			String busy;
			IOException unreachable = null;
			try {
				Answer answer = transport.post(json);
				if (answer.status() >= 200 && answer.status() < 300) {
					if (attempt > 1) {
						log.accept("Flint: joined through Mojang's session server on try " + attempt + " of " + ATTEMPTS);
					}
					return;
				}
				if (answer.status() != 429 && answer.status() < 500) {
					refuse(answer);
					// No error in the answer: what authlib 1.5.21 did, a join that went through.
					return;
				}
				busy = "answered " + answer.status();
			} catch (IOException e) {
				unreachable = e;
				busy = "couldn't be reached (" + e.getClass().getSimpleName() + ")";
			}
			long wait = attempt < ATTEMPTS ? WAITS_MS[attempt - 1] : -1;
			if (wait < 0 || clock.nowMs() - started + wait > BUDGET_MS) {
				log.accept("Flint: Mojang's session server " + busy + " joining a server (try " + attempt + " of " + ATTEMPTS + "), giving up");
				throw new AuthenticationUnavailableException("Cannot contact authentication server", unreachable);
			}
			log.accept("Flint: Mojang's session server " + busy + " joining a server (try " + attempt + " of " + ATTEMPTS + "), trying again in " + wait / 1000.0 + " s");
			try {
				pause.sleep(wait);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AuthenticationUnavailableException("Cannot contact authentication server", unreachable);
			}
		}
	}

	/** A 4xx with an error in it, thrown as authlib 1.5.21 throws it; returns for one without. */
	static void refuse(Answer answer) throws AuthenticationException {
		JsonObject body;
		try {
			JsonElement parsed = new JsonParser().parse(answer.body());
			body = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (RuntimeException notJson) {
			body = null;
		}
		String error = text(body, "error");
		if (error == null || error.isBlank()) {
			return;
		}
		String message = text(body, "errorMessage");
		if ("UserMigratedException".equals(text(body, "cause"))) {
			throw new UserMigratedException(message);
		}
		if ("ForbiddenOperationException".equals(error)) {
			throw new InvalidCredentialsException(message);
		}
		throw new AuthenticationException(message);
	}

	private static String text(JsonObject body, String key) {
		if (body == null || !body.has(key) || !body.get(key).isJsonPrimitive()) {
			return null;
		}
		return body.get(key).getAsString();
	}

	/** authlib's request: JSON POST, its 15 s for each step, on a connection of its own. */
	private static Answer post(URL url, Proxy proxy, String json) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
		try {
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setUseCaches(false);
			connection.setInstanceFollowRedirects(false);
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Connection", "close");
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
			connection.setDoOutput(true);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(bytes);
			}
			int status = connection.getResponseCode();
			InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			return new Answer(status, in == null ? "" : read(in));
		} finally {
			connection.disconnect();
		}
	}

	private static String read(InputStream in) throws IOException {
		try (in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, Math.min(n, MAX_BODY - out.size()));
				if (out.size() >= MAX_BODY) {
					break;
				}
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}
}

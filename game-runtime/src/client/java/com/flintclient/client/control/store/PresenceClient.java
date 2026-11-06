package com.flintclient.client.control.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Flint presence through Flint's servers ({@link FlintServices}; Backend migration 20261031005000_flint_presence): who is on
 * Flint now, so Flint players see each other's Flint mark. {@link #announce} proves the account the way a Minecraft server
 * does: it tells Mojang's session server that the player joins the server id of a challenge Flint's services issued (with the
 * game's own access token, sent to Mojang only), and the services ask Mojang which player that was (Backend 6500). It answers
 * a token for {@link #heartbeat} (which also answers the player's own mark now, Backend 7100) and
 * {@link #leave}. {@link #lookup} answers which of up to {@link #MAX_LOOKUP} players are on Flint, with their marks.
 * Off the game's thread, with {@link ProfileShareClient}'s transport (short timeouts, no redirects, capped answers); answers are
 * untrusted and checked. Never logs a token. Version-neutral.
 */
public final class PresenceClient {
	/** The most players one lookup asks about (the server reads no more). */
	public static final int MAX_LOOKUP = 200;
	static final URI MOJANG_JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");

	/** How a request ended. */
	public enum Status {
		OK,
		/** This Flint was built without the services. */
		NOT_CONFIGURED,
		/** Flint's servers or Mojang's couldn't be reached. */
		OFFLINE,
		/** Mojang didn't confirm the account (an offline account, or a token Mojang refused). */
		UNVERIFIED,
		/** The token is no longer valid: announce again. */
		EXPIRED,
		/** The server is limiting requests for now. */
		BUSY,
		/** An answer Flint doesn't understand. */
		FAILED
	}

	/** An announce: for {@link Status#OK}, the token and the player's own mark ("default", "staff"…). */
	public record Announced(Status status, @Nullable String token, String tag) {
		static Announced of(Status status) {
			return new Announced(status, null, "default");
		}
	}

	/**
	 * A heartbeat: its status and, for {@link Status#OK}, the player's own mark now ("default", "staff"…), so a mark Flint
	 * gives or takes away shows within a heartbeat instead of at the next announce. Null when the server didn't say (an
	 * older server) or said something that isn't a mark name: the mark then stays as it is.
	 */
	public record Beat(Status status, @Nullable String tag) {
		static Beat of(Status status) {
			return new Beat(status, null);
		}
	}

	/** A lookup: for {@link Status#OK}, the players on Flint among those asked about, with their marks. */
	public record Found(Status status, Map<UUID, String> players) {
		static Found of(Status status) {
			return new Found(status, Map.of());
		}
	}

	private final @Nullable FlintServices services;
	private final ProfileShareClient.Transport transport;

	public PresenceClient(@Nullable FlintServices services, ProfileShareClient.Transport transport) {
		this.services = services;
		this.transport = transport;
	}

	private static volatile @Nullable PresenceClient shared;

	/** The client for the services this jar was built with (made on first use). */
	public static PresenceClient get() {
		PresenceClient client = shared;
		if (client == null) {
			client = DemoServices.on() ? new PresenceClient(DemoServices.SERVICES, DemoServices.transport())
				: new PresenceClient(FlintServices.bundled(), ProfileShareClient.http());
			shared = client;
		}
		return client;
	}

	public boolean configured() {
		return services != null;
	}

	/**
	 * Whether any player may be asked about: services on this computer (development, QA's offline players). Flint's own
	 * servers only know Mojang accounts, so only version-4 UUIDs are asked about there ({@code PresenceTargets}).
	 */
	public boolean asksAnyone() {
		return services != null && services.loopback();
	}

	/** A challenge from Flint's services: its id, and the server id to join at Mojang (Backend 20261104006500). */
	record Challenge(String id, String serverId) {
	}

	/** {@code {"ok": true, "challenge": "<uuid>", "server_id": "<40 hex>"}}, or null for anything else. */
	static @Nullable Challenge readChallenge(ProfileShareClient.Reply reply) {
		JsonObject answer = answer(reply);
		if (answer == null || !Boolean.TRUE.equals(JsonFields.bool(answer, "ok"))) {
			return null;
		}
		String id = JsonFields.string(answer, "challenge");
		String serverId = JsonFields.string(answer, "server_id");
		if (id == null || !id.matches("[0-9a-f-]{36}") || serverId == null || !serverId.matches("[0-9a-f]{40}")) {
			return null;
		}
		return new Challenge(id, serverId);
	}

	/**
	 * "I am on Flint" as {@code player} ({@code name}), proven with Mojang using {@code accessToken}; {@code visible}: other
	 * Flint players may see it ("Show I'm on Flint"). The token proves the account either way (daily rewards use it).
	 *
	 * <p>The proof is a challenge Flint's services issue (single use, for two minutes): the game joins its server id at Mojang,
	 * then announces with the challenge, and the services ask Mojang who joined it. A server id the game picked itself was
	 * no proof (any Minecraft server the player joins learns one); the services refuse that since 20261104006400.
	 */
	public CompletableFuture<Announced> announce(UUID player, String name, String accessToken, boolean visible) {
		if (services == null) {
			return CompletableFuture.completedFuture(Announced.of(Status.NOT_CONFIGURED));
		}
		if (!name.matches("[A-Za-z0-9_]{1,16}") || accessToken.isBlank()) {
			return CompletableFuture.completedFuture(Announced.of(Status.UNVERIFIED));
		}
		JsonObject ask = new JsonObject();
		ask.addProperty("p_purpose", "presence");
		return call("flint_signin_challenge", ask).thenCompose(issued -> {
			Challenge challenge = readChallenge(issued);
			if (challenge == null) {
				// No challenge: the services are limiting (busy), don't have the function yet, or answered something else.
				boolean busy = issued.status() == 200 ? readStatus(issued) == Status.BUSY : failed(issued) == Status.BUSY;
				return CompletableFuture.completedFuture(Announced.of(busy ? Status.BUSY : Status.FAILED));
			}
			JsonObject join = new JsonObject();
			join.addProperty("accessToken", accessToken);
			join.addProperty("selectedProfile", player.toString().replace("-", ""));
			join.addProperty("serverId", challenge.serverId());
			Map<String, String> headers = new LinkedHashMap<>();
			headers.put("Content-Type", "application/json");
			return transport.post(MOJANG_JOIN, headers, join.toString()).thenCompose(reply -> {
				if (reply.status() != 204 && reply.status() != 200) {
					// 403: Mojang refused the token (an offline or expired session); 429 or 5xx: Mojang is busy, asked later.
					return CompletableFuture.completedFuture(Announced.of(reply.status() == 429 || reply.status() >= 500 ? Status.BUSY : Status.UNVERIFIED));
				}
				JsonObject body = new JsonObject();
				body.addProperty("p_uuid", player.toString());
				body.addProperty("p_username", name);
				body.addProperty("p_challenge", challenge.id());
				body.addProperty("p_visible", visible);
				return call("flint_presence_announce_v2", body).thenApply(PresenceClient::readAnnounced);
			});
		}).exceptionally(error -> Announced.of(status(error)));
	}

	/** "Still on Flint", with the token {@link #announce} gave, and whether others may see it now. */
	public CompletableFuture<Beat> heartbeat(UUID player, String token, boolean visible) {
		if (services == null) {
			return CompletableFuture.completedFuture(Beat.of(Status.NOT_CONFIGURED));
		}
		JsonObject body = new JsonObject();
		body.addProperty("p_visible", visible);
		body.addProperty("p_uuid", player.toString());
		body.add("p_token", new JsonPrimitive(token));
		return call("flint_presence_heartbeat", body).thenApply(PresenceClient::readBeat)
			.exceptionally(error -> Beat.of(status(error)));
	}

	/** "Off Flint now". */
	public CompletableFuture<Status> leave(UUID player, String token) {
		return tokenCall("flint_presence_leave", player, token, new JsonObject());
	}

	/** Which of {@code players} (the first {@link #MAX_LOOKUP}) are on Flint now. */
	public CompletableFuture<Found> lookup(Collection<UUID> players) {
		if (services == null) {
			return CompletableFuture.completedFuture(Found.of(Status.NOT_CONFIGURED));
		}
		if (players.isEmpty()) {
			return CompletableFuture.completedFuture(new Found(Status.OK, Map.of()));
		}
		JsonArray uuids = new JsonArray();
		for (UUID player : players) {
			if (uuids.size() == MAX_LOOKUP) {
				break;
			}
			uuids.add(player.toString());
		}
		JsonObject body = new JsonObject();
		body.add("p_uuids", uuids);
		return call("flint_presence_lookup", body).thenApply(PresenceClient::readFound).exceptionally(error -> Found.of(status(error)));
	}

	private CompletableFuture<Status> tokenCall(String function, UUID player, String token, JsonObject body) {
		if (services == null) {
			return CompletableFuture.completedFuture(Status.NOT_CONFIGURED);
		}
		body.addProperty("p_uuid", player.toString());
		body.add("p_token", new JsonPrimitive(token));
		return call(function, body).thenApply(PresenceClient::readStatus).exceptionally(PresenceClient::status);
	}

	/** Calls database function {@code function} of Flint's services with {@code body} (null without the services). */
	CompletableFuture<ProfileShareClient.Reply> call(String function, JsonObject body) {
		FlintServices s = services;
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("apikey", s.anonKey());
		headers.put("Authorization", "Bearer " + s.anonKey());
		headers.put("Content-Type", "application/json");
		headers.put("Accept", "application/json");
		try {
			return transport.post(s.rpc(function), headers, body.toString());
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** {@code {"ok": true, "token": …, "tag": …}} or {@code {"ok": false, "error": …}}. */
	static Announced readAnnounced(ProfileShareClient.Reply reply) {
		JsonObject answer = answer(reply);
		if (answer == null) {
			return Announced.of(failed(reply));
		}
		if (Boolean.TRUE.equals(JsonFields.bool(answer, "ok"))) {
			String token = JsonFields.string(answer, "token");
			String tag = JsonFields.string(answer, "tag");
			if (token == null || !token.matches("[0-9a-f]{64}")) {
				return Announced.of(Status.FAILED);
			}
			return new Announced(Status.OK, token, tag == null ? "default" : tag.toLowerCase(Locale.ROOT));
		}
		return Announced.of(error(JsonFields.string(answer, "error")));
	}

	/** {@code {"ok": true, "tag": …}} ({@code tag} optional) or {@code {"ok": false, "error": …}}. */
	static Beat readBeat(ProfileShareClient.Reply reply) {
		Status status = readStatus(reply);
		if (status != Status.OK) {
			return Beat.of(status);
		}
		JsonObject answer = answer(reply);
		String tag = answer == null ? null : JsonFields.string(answer, "tag");
		return new Beat(Status.OK, tag != null && tag.matches("[A-Za-z_]{1,32}") ? tag.toLowerCase(Locale.ROOT) : null);
	}

	/** {@code {"ok": true}} or {@code {"ok": false, "error": …}}. */
	static Status readStatus(ProfileShareClient.Reply reply) {
		JsonObject answer = answer(reply);
		if (answer == null) {
			return failed(reply);
		}
		return Boolean.TRUE.equals(JsonFields.bool(answer, "ok")) ? Status.OK : error(JsonFields.string(answer, "error"));
	}

	/** {@code {"<uuid>": "<mark>", …}}; entries that aren't a UUID and a short mark name are left out. */
	static Found readFound(ProfileShareClient.Reply reply) {
		JsonObject answer = answer(reply);
		if (answer == null) {
			return Found.of(failed(reply));
		}
		Map<UUID, String> players = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : answer.entrySet()) {
			if (players.size() == MAX_LOOKUP) {
				break;
			}
			JsonElement tag = entry.getValue();
			if (!tag.isJsonPrimitive() || !tag.getAsJsonPrimitive().isString() || !tag.getAsString().matches("[a-z_]{1,32}")) {
				continue;
			}
			try {
				players.put(UUID.fromString(entry.getKey()), tag.getAsString());
			} catch (IllegalArgumentException ignored) {
				// Not a UUID: skipped.
			}
		}
		return new Found(Status.OK, players);
	}

	static Status error(@Nullable String error) {
		return switch (error == null ? "" : error) {
			case "unverified", "invalid" -> Status.UNVERIFIED;
			case "expired" -> Status.EXPIRED;
			case "rate_limited" -> Status.BUSY;
			default -> Status.FAILED;
		};
	}

	/** The JSON object of a 200 answer, or null. */
	static @Nullable JsonObject answer(ProfileShareClient.Reply reply) {
		if (reply.status() != 200 || reply.body().length() > ProfileShareClient.MAX_ANSWER_BYTES) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(reply.body());
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** What a non-answer means: a limit, or a failure. */
	static Status failed(ProfileShareClient.Reply reply) {
		int code = reply.status();
		return code == 429 || code == 503 || code == 400 && reply.body().contains("rate_limited") ? Status.BUSY : Status.FAILED;
	}

	static Status status(Throwable error) {
		ProfileShareClient.Result result = ProfileShareClient.failure(error);
		return result.status() == ProfileShareClient.Status.OFFLINE ? Status.OFFLINE : Status.FAILED;
	}
}

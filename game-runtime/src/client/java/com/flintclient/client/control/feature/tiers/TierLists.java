/*
 * Based on TierTagger (https://github.com/mctiers-dev/TierTagger), Copyright (c) 2025 MCTiers, mctiers.com.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.flintclient.client.control.feature.tiers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Tier Tagger's tier lists: a player's tiers looked up once on the chosen list (MCTiers or SubTiers) and kept, the list's
 * gamemodes, and a player search. Only players with an online-mode (version 4) UUID are looked up, and only while Tier
 * Tagger is on; a list that fails or doesn't know someone gives nothing, never a guess. Requests go over HTTPS with
 * timeouts, off the game's thread; answers are parsed strictly and bounded. Pure Java (no Minecraft).
 */
public final class TierLists {
	/** The tier lists Tier Tagger can show, in the order of the card's Tier list setting. */
	public enum TierList {
		MCTIERS("MCTiers", "https://mctiers.com/api"),
		SUBTIERS("SubTiers", "https://subtiers.net/api");

		private final String title;
		private final String api;

		TierList(String title, String api) {
			this.title = title;
			this.api = api;
		}

		public String title() {
			return title;
		}
	}

	/** One gamemode's tier: tier 1..5 (1 best), position 0 (high) or 1 (low), the peak reached, and whether it's retired. */
	public record Ranking(int tier, int pos, @Nullable @SerializedName("peak_tier") Integer peakTier, @Nullable @SerializedName("peak_pos") Integer peakPos,
		long attained, boolean retired) {
		/** Lower is better. */
		public int comparableTier() {
			return tier * 2 + pos;
		}

		/** Lower is better; no peak counts as the worst. */
		public int comparablePeak() {
			return peakTier == null || peakPos == null ? Integer.MAX_VALUE : peakTier * 2 + peakPos;
		}
	}

	/** A ranking with the gamemode it's in. */
	public record Named(String mode, Ranking ranking) {
	}

	/** A gamemode as the list names it. */
	public record Mode(String id, String title) {
	}

	/** A searched player's profile on the list. */
	public record Profile(String uuid, String name, Map<String, Ranking> rankings, @Nullable String region, int points, int overall) {
	}

	/** When the highest tier shows instead of the chosen gamemode's. */
	public enum Highest {
		NEVER,
		/** Only when the player isn't ranked in the chosen gamemode. */
		NOT_FOUND,
		ALWAYS
	}

	private static final Gson GSON = new Gson();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	/** At most this many players are kept; the cache starts over past it (a long session in busy lobbies). */
	private static final int MAX_PLAYERS = 4096;
	private static final int MAX_BODY = 256 * 1024;
	/** At most this many gamemodes are kept per player (the lists have about ten). */
	private static final int MAX_MODES = 32;
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
		.followRedirects(HttpClient.Redirect.NEVER).build();

	private static volatile TierList list = TierList.MCTIERS;
	private static final Map<UUID, Optional<Map<String, Ranking>>> TIERS = new ConcurrentHashMap<>();
	private static volatile List<Mode> modes = List.of();
	private static volatile boolean modesAsked;
	/** Lookups on their way: a busy lobby asks a few at a time (the rest on later frames), never hundreds at once. */
	private static final java.util.concurrent.atomic.AtomicInteger IN_FLIGHT = new java.util.concurrent.atomic.AtomicInteger();
	private static final int MAX_IN_FLIGHT = 6;

	private TierLists() {
	}

	/** The list in use; switching drops what the other list said. */
	public static void use(TierList chosen) {
		if (chosen != list) {
			list = chosen;
			TIERS.clear();
			modes = List.of();
			modesAsked = false;
		}
	}

	public static TierList list() {
		return list;
	}

	/** Forgets every player's tiers (they're looked up again when seen). */
	public static void clear() {
		TIERS.clear();
	}

	/** The list's gamemodes once it answered (asked once per list), else empty. */
	public static List<Mode> modes() {
		if (!modesAsked) {
			modesAsked = true;
			TierList asked = list;
			get(asked.api + "/v2/mode/list").thenAccept(body -> {
				JsonObject all = GSON.fromJson(body, JsonObject.class);
				List<Mode> found = new ArrayList<>();
				for (Map.Entry<String, JsonElement> e : all.entrySet()) {
					if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("title")) {
						found.add(new Mode(e.getKey(), e.getValue().getAsJsonObject().get("title").getAsString()));
					}
				}
				if (asked == list) {
					modes = List.copyOf(found);
				}
			}).exceptionally(t -> null);
		}
		return modes;
	}

	/**
	 * A player's rankings, once the list answered: looked up the first time they're asked for (online-mode UUIDs only),
	 * empty meanwhile and when the list doesn't have them.
	 */
	public static Optional<Map<String, Ranking>> rankings(UUID player) {
		if (player.version() != 4) {
			return Optional.empty();
		}
		if (TIERS.size() > MAX_PLAYERS) {
			TIERS.clear();
		}
		Optional<Map<String, Ranking>> known = TIERS.get(player);
		if (known != null) {
			return known;
		}
		if (IN_FLIGHT.get() >= MAX_IN_FLIGHT) {
			return Optional.empty();
		}
		return TIERS.computeIfAbsent(player, id -> {
			TierList asked = list;
			IN_FLIGHT.incrementAndGet();
			get(asked.api + "/v2/profile/" + id + "/rankings").whenComplete((body, error) -> IN_FLIGHT.decrementAndGet()).thenAccept(body -> {
				Map<String, Ranking> found = GSON.fromJson(body, new TypeToken<Map<String, Ranking>>() { }.getType());
				if (asked == list && found != null) {
					TIERS.put(id, Optional.of(kept(found)));
				}
			}).exceptionally(t -> null);
			return Optional.empty();
		});
	}

	/** Looks a player up by name (and keeps their tiers); fails for an unknown name or an unusable answer. */
	public static CompletableFuture<Profile> search(String name) {
		if (!NAME.matcher(name).matches()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("not a Minecraft name"));
		}
		TierList asked = list;
		return get(asked.api + "/v2/profile/by-name/" + URLEncoder.encode(name, StandardCharsets.UTF_8)).thenApply(body -> {
			Profile p = GSON.fromJson(body, Profile.class);
			if (p == null || p.uuid() == null || p.name() == null || p.rankings() == null) {
				throw new IllegalStateException("no such player");
			}
			Profile shown = new Profile(p.uuid(), p.name(), kept(p.rankings()), p.region(), p.points(), p.overall());
			UUID id = uuid(p.uuid());
			if (id != null && asked == list) {
				TIERS.put(id, Optional.of(shown.rankings()));
			}
			return shown;
		});
	}

	/**
	 * Which ranking shows for someone with {@code rankings}: the chosen gamemode's, or their highest as {@code highest}
	 * says; empty when nothing should show.
	 */
	public static Optional<Named> shown(Map<String, Ranking> rankings, String mode, Highest highest) {
		Ranking chosen = rankings.get(mode);
		Optional<Named> best = highestOf(rankings);
		if (chosen == null) {
			return highest != Highest.NEVER ? best : Optional.empty();
		}
		return highest == Highest.ALWAYS && best.isPresent() ? best : Optional.of(new Named(mode, chosen));
	}

	/** The best ranking over every gamemode. */
	public static Optional<Named> highestOf(Map<String, Ranking> rankings) {
		return rankings.entrySet().stream().filter(e -> e.getKey() != null && e.getValue() != null)
			.min(Comparator.comparingInt(e -> e.getValue().comparableTier())).map(e -> new Named(e.getKey(), e.getValue()));
	}

	/** A ranking as it reads: "HT3", "LT1", or a retired one's peak as "RHT2". */
	public static String label(Ranking r) {
		if (r.retired() && r.peakTier() != null && r.peakPos() != null) {
			return label(r.peakTier(), r.peakPos(), true);
		}
		return label(r.tier(), r.pos(), false);
	}

	public static String label(int tier, int pos, boolean retired) {
		return (retired ? "R" : "") + (pos == 0 ? "H" : "L") + "T" + tier;
	}

	/** The list's points for a ranking (as its site counts them). */
	public static int points(Ranking r) {
		return switch (r.tier()) {
			case 1 -> r.pos() == 0 ? 60 : 45;
			case 2 -> r.pos() == 0 ? 30 : 20;
			case 3 -> r.pos() == 0 ? 10 : 6;
			case 4 -> r.pos() == 0 ? 4 : 3;
			case 5 -> r.pos() == 0 ? 2 : 1;
			default -> 0;
		};
	}

	private static @Nullable UUID uuid(String text) {
		try {
			if (text.length() == 32) {
				return new UUID(Long.parseUnsignedLong(text.substring(0, 16), 16), Long.parseUnsignedLong(text.substring(16), 16));
			}
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * The rankings worth keeping for one player: real tiers (1..5, high or low) under a short gamemode id, at most
	 * {@link #MAX_MODES} of them, so an odd answer can't fill memory one player at a time.
	 */
	static Map<String, Ranking> kept(Map<String, Ranking> found) {
		Map<String, Ranking> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Ranking> e : found.entrySet()) {
			Ranking r = e.getValue();
			if (out.size() >= MAX_MODES) {
				break;
			}
			if (e.getKey() != null && !e.getKey().isEmpty() && e.getKey().length() <= 32 && r != null && r.tier() >= 1 && r.tier() <= 5 && r.pos() >= 0
				&& r.pos() <= 1) {
				out.put(e.getKey(), r);
			}
		}
		return Map.copyOf(out);
	}

	/** An HTTPS GET's body: only a 200 answer of a sane size counts, and a bigger one is cut off, never read whole. */
	private static CompletableFuture<String> get(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
		HttpResponse.BodyHandler<String> body = info -> info.statusCode() == 200 ? new CappedBody(MAX_BODY) : HttpResponse.BodySubscribers.replacing("");
		return CLIENT.sendAsync(request, body).thenApply(r -> {
			if (r.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + r.statusCode());
			}
			return r.body();
		});
	}

	/** A body read as UTF-8 that fails (and stops the download) once it passes {@code max} bytes. */
	static final class CappedBody implements HttpResponse.BodySubscriber<String> {
		private final int max;
		private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		private final CompletableFuture<String> result = new CompletableFuture<>();
		private java.util.concurrent.Flow.@Nullable Subscription subscription;

		CappedBody(int max) {
			this.max = max;
		}

		@Override
		public java.util.concurrent.CompletionStage<String> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
			subscription = s;
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<java.nio.ByteBuffer> items) {
			if (result.isDone()) {
				return;
			}
			for (java.nio.ByteBuffer item : items) {
				if (bytes.size() + (long) item.remaining() > max) {
					if (subscription != null) {
						subscription.cancel();
					}
					result.completeExceptionally(new IllegalStateException("answer larger than " + max + " bytes"));
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
			result.complete(bytes.toString(StandardCharsets.UTF_8));
		}
	}
}

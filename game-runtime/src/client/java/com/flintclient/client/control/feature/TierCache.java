package com.flintclient.client.control.feature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tier Tagger's provider-neutral core: a looked-up player's tiers in one shape whatever tier list they came from, kept for a
 * while so a busy lobby doesn't ask again for every frame or every player. Failures are kept too (shorter), so a player
 * a list doesn't know, or a list that is down or limiting requests, isn't asked again at once. No provider is connected
 * yet (see the module's note): nothing here makes a request. Pure Java.
 */
public final class TierCache {
	/** What a lookup said. Only {@link #FOUND} carries tiers; nothing is ever made up for the others. */
	public enum Status {
		FOUND,
		/** The list doesn't have this player. */
		NOT_FOUND,
		/** The list couldn't be reached, or answered something unusable. */
		UNAVAILABLE,
		/** The list asked us to slow down. */
		RATE_LIMITED
	}

	/** A tier like HT1 (high tier 1) or LT5 (low tier 5); 1 is the best. */
	public record Tier(boolean high, int level) {
		public Tier {
			if (level < 1 || level > 5) {
				throw new IllegalArgumentException("tier level " + level);
			}
		}

		/** "HT1".."LT5", or null for anything else. */
		public static @Nullable Tier parse(String text) {
			if (text == null || text.length() != 3 || text.charAt(1) != 'T' && text.charAt(1) != 't') {
				return null;
			}
			char hl = Character.toUpperCase(text.charAt(0));
			int level = text.charAt(2) - '0';
			if (hl != 'H' && hl != 'L' || level < 1 || level > 5) {
				return null;
			}
			return new Tier(hl == 'H', level);
		}

		public String label() {
			return (high ? "HT" : "LT") + level;
		}

		/** Higher is better: HT1 10, LT1 9, HT2 8 ... LT5 1. */
		public int rank() {
			return (6 - level) * 2 - (high ? 0 : 1);
		}
	}

	/** One player's result: its status and, when found, the tier per gamemode ("sword", "vanilla"...). */
	public record Result(Status status, Map<String, Tier> tiers, long at) {
		public Optional<Tier> tier(String gamemode) {
			return Optional.ofNullable(tiers.get(gamemode));
		}

		/** The best tier in any gamemode. */
		public Optional<Map.Entry<String, Tier>> highest() {
			return tiers.entrySet().stream().max((a, b) -> Integer.compare(a.getValue().rank(), b.getValue().rank()));
		}
	}

	/** How long a found result is kept. */
	public static final long FOUND_MS = 30 * 60_000L;
	/** How long a "not on the list" is kept. */
	public static final long NOT_FOUND_MS = 10 * 60_000L;
	/** How long a failure is kept before asking again. */
	public static final long FAILURE_MS = 60_000L;
	static final int MAX = 512;

	private final Map<UUID, Result> results = new LinkedHashMap<>(64, 0.75F, true);

	/** The kept result for {@code player}, or null when there's none or it has expired. */
	public synchronized @Nullable Result get(UUID player, long nowMs) {
		Result r = results.get(player);
		if (r == null) {
			return null;
		}
		long keep = switch (r.status()) {
			case FOUND -> FOUND_MS;
			case NOT_FOUND -> NOT_FOUND_MS;
			default -> FAILURE_MS;
		};
		if (nowMs - r.at() > keep) {
			results.remove(player);
			return null;
		}
		return r;
	}

	public synchronized void put(UUID player, Result result) {
		results.put(player, result);
		while (results.size() > MAX) {
			results.remove(results.keySet().iterator().next());
		}
	}

	/** What a nametag or the tab list shows: the tier label, or the reason there is none. */
	public static String label(@Nullable Result result, String gamemode, boolean highestWhenMissing) {
		if (result == null) {
			return "…";
		}
		return switch (result.status()) {
			case FOUND -> result.tier(gamemode).map(Tier::label)
				.or(() -> highestWhenMissing ? result.highest().map(e -> e.getValue().label()) : Optional.empty()).orElse("Not Found");
			case NOT_FOUND -> "Not Found";
			case RATE_LIMITED, UNAVAILABLE -> "Not Available";
		};
	}
}

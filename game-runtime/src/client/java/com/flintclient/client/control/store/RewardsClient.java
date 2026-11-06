package com.flintclient.client.control.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Daily rewards and the Merit balance through Flint's servers (Backend migration 20261031005100_daily_rewards_store_merit):
 * {@link #status} reads them, {@link #claim} claims today's reward. The account is proven with the token of the Flint presence
 * announce ({@link PresenceClient}). Off the game's thread, through the presence client's transport; answers are untrusted
 * and checked (numbers bounded, times parsed or left out). Version-neutral.
 */
public final class RewardsClient {
	/** The most days a track may list (the server sends a week). */
	static final int MAX_TRACK = 14;

	/** One day of the streak track and its reward in Merits. */
	public record Day(int day, int reward) {
	}

	/**
	 * The player's rewards: their Merit balance, the streak now and their best, whether today's reward is there, the day and
	 * reward it gives, when the next one comes and when the streak runs out (epoch milliseconds by the server's clock, or -1),
	 * the server's time when it answered, the largest daily reward, the week of the track to show, what a claim just gave
	 * (day 0 when nothing was claimed), and why rewards can't be collected now (null when they can): {@code "disabled"} while
	 * Flint has them switched off, {@code "store_account_required"} before the account's first Store sign-in, or another of
	 * the server's reasons.
	 */
	public record Rewards(long balance, int streak, int best, boolean canClaim, int nextDay, int nextReward, long nextClaimAt,
		long streakEndsAt, long serverTime, int maxReward, List<Day> track, int claimedDay, int claimedReward,
		@Nullable String unavailable) {
		public Rewards(long balance, int streak, int best, boolean canClaim, int nextDay, int nextReward, long nextClaimAt,
			long streakEndsAt, long serverTime, int maxReward, List<Day> track, int claimedDay, int claimedReward) {
			this(balance, streak, best, canClaim, nextDay, nextReward, nextClaimAt, streakEndsAt, serverTime, maxReward, track,
				claimedDay, claimedReward, null);
		}
	}

	/**
	 * An answer: for {@link PresenceClient.Status#OK}, the rewards ({@code rewards} null otherwise). {@code refusal}: the server's
	 * own reason when it answered but said no for a reason of its own ({@code "ip_daily_limit"}, {@code "daily_cap_reached"},
	 * {@code "disabled"}…), so the screen can say why instead of offering the button again.
	 */
	public record Answer(PresenceClient.Status status, @Nullable Rewards rewards, @Nullable String refusal) {
		public Answer(PresenceClient.Status status, @Nullable Rewards rewards) {
			this(status, rewards, null);
		}

		static Answer of(PresenceClient.Status status) {
			return new Answer(status, null);
		}
	}

	private final PresenceClient presence;

	public RewardsClient(PresenceClient presence) {
		this.presence = presence;
	}

	public boolean configured() {
		return presence.configured();
	}

	/** {@code player}'s rewards, proven with presence token {@code token}. */
	public CompletableFuture<Answer> status(UUID player, String token) {
		return send("merit_daily_status", player, token);
	}

	/** Claims today's reward. Too early, the answer is the rewards as they are (nothing claimed). */
	public CompletableFuture<Answer> claim(UUID player, String token) {
		return send("merit_daily_claim", player, token);
	}

	private CompletableFuture<Answer> send(String function, UUID player, String token) {
		if (!presence.configured()) {
			return CompletableFuture.completedFuture(Answer.of(PresenceClient.Status.NOT_CONFIGURED));
		}
		JsonObject body = new JsonObject();
		body.addProperty("p_uuid", player.toString());
		body.addProperty("p_token", token);
		return presence.call(function, body).thenApply(RewardsClient::read).exceptionally(error -> Answer.of(PresenceClient.status(error)));
	}

	/** An answer as the header of the migration describes it; "already_claimed" carries the rewards too. */
	static Answer read(ProfileShareClient.Reply reply) {
		JsonObject answer = PresenceClient.answer(reply);
		if (answer == null) {
			return Answer.of(PresenceClient.failed(reply));
		}
		boolean ok = Boolean.TRUE.equals(JsonFields.bool(answer, "ok"));
		String error = JsonFields.string(answer, "error");
		if (!ok && !"already_claimed".equals(error)) {
			PresenceClient.Status status = PresenceClient.error(error);
			// A refusal of the server's own (not a limit, an expired proof or a bad request): kept, so it can be explained.
			String refusal = status == PresenceClient.Status.FAILED && error != null && error.matches("[a-z_]{1,40}") ? error : null;
			return new Answer(status, null, refusal);
		}
		Rewards rewards = rewards(answer);
		return rewards == null ? Answer.of(PresenceClient.Status.FAILED) : new Answer(PresenceClient.Status.OK, rewards);
	}

	private static @Nullable Rewards rewards(JsonObject a) {
		long balance = number(a, "balance", -1, Long.MAX_VALUE / 4);
		int nextDay = (int) number(a, "next_day", -1, 1_000_000);
		if (balance < 0 || nextDay < 1) {
			return null;
		}
		List<Day> track = new ArrayList<>();
		JsonElement days = a.get("track");
		if (days instanceof JsonArray list) {
			for (JsonElement d : list) {
				if (track.size() == MAX_TRACK) {
					break;
				}
				if (d instanceof JsonObject o) {
					int day = (int) number(o, "day", -1, 1_000_000);
					int reward = (int) number(o, "reward", -1, 1_000_000);
					if (day >= 1 && reward >= 0) {
						track.add(new Day(day, reward));
					}
				}
			}
		}
		int claimedDay = 0;
		int claimedReward = 0;
		if (a.get("claimed") instanceof JsonObject claimed) {
			claimedDay = (int) Math.max(0, number(claimed, "day", 0, 1_000_000));
			claimedReward = (int) Math.max(0, number(claimed, "reward", 0, 1_000_000));
		}
		return new Rewards(balance, (int) Math.max(0, number(a, "streak", 0, 1_000_000)), (int) Math.max(0, number(a, "best", 0, 1_000_000)),
			Boolean.TRUE.equals(JsonFields.bool(a, "can_claim")), nextDay, (int) Math.max(0, number(a, "next_reward", 0, 1_000_000)),
			time(a, "next_claim_at"), time(a, "streak_ends_at"), time(a, "server_time"), (int) Math.max(0, number(a, "max_reward", 0, 1_000_000)),
			List.copyOf(track), claimedDay, claimedReward, unavailable(a));
	}

	/**
	 * Why rewards can't be collected, or null: {@code "disabled"} whenever {@code "enabled"} is false (the server names a
	 * missing Store sign-in first, but while rewards are off that isn't what stops the player), else {@code "unavailable"}.
	 */
	private static @Nullable String unavailable(JsonObject a) {
		if (Boolean.FALSE.equals(JsonFields.bool(a, "enabled"))) {
			return "disabled";
		}
		String reason = JsonFields.string(a, "unavailable");
		return reason == null ? null : reason.matches("[a-z_]{1,40}") ? reason : "disabled";
	}

	/** A whole number field within [min, max], or {@code fallback}. */
	private static long number(JsonObject o, String key, long fallback, long max) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			long v = e.getAsLong();
			return v < 0 || v > max ? fallback : v;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	/** An ISO time field in epoch milliseconds, or -1 (null or unreadable). */
	private static long time(JsonObject o, String key) {
		String text = JsonFields.string(o, key);
		if (text == null) {
			return -1;
		}
		try {
			return OffsetDateTime.parse(text).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			return -1;
		}
	}
}

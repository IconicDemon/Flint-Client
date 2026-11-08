package com.flintclient.client.control.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Flint's servers as they answer the game, in memory, for QA only: with {@code -Dflintqa.services=demo} the presence and
 * rewards clients talk to this instead of the network, so the Daily Rewards screens can be tried and photographed before the
 * Backend is deployed. It follows the Backend's rewards rules (migration 20261104006700: 5, 5, 10, 15, 20, 25, 40, repeating
 * every 7 days; a missed day starts again at day 1; 24 hours between claims). {@code -Dflintqa.rewards.day=N} starts the streak at day N with today's reward waiting ({@code claimed}
 * adds {@code -Dflintqa.rewards.claimed=true}). {@code -Dflintqa.rewards.state=S} shows a state that isn't the rewards instead:
 * {@code signedout} (Mojang refuses the account), {@code offline} (nothing answers), {@code loading} (no answer ever comes),
 * {@code paused} (rewards switched off), {@code store} (no Store sign-in yet) or {@code refuse} (a claim refused as this
 * network's daily limit). Never used without the property. Version-neutral.
 */
public final class DemoServices {
	public static final FlintServices SERVICES = new FlintServices("http://127.0.0.1", "flint-qa-demo");
	private static final long HOUR = 3_600_000L;
	private static final String STATE = System.getProperty("flintqa.rewards.state", "");

	private long balance = 1_250;
	private int day;
	private int best;
	private long lastClaim;

	private DemoServices() {
		day = Integer.getInteger("flintqa.rewards.day", 3);
		best = Math.max(day, 9);
		boolean claimed = Boolean.getBoolean("flintqa.rewards.claimed");
		lastClaim = day == 0 ? 0 : System.currentTimeMillis() - (claimed ? 3 * HOUR + 17 * 60_000L : 25 * HOUR);
	}

	/** Whether this game runs against the demo (QA only). */
	public static boolean on() {
		return "demo".equals(System.getProperty("flintqa.services"));
	}

	/** The transport that answers as Flint's servers and Mojang would. */
	public static ProfileShareClient.Transport transport() {
		DemoServices demo = new DemoServices();
		return (uri, headers, body) -> switch (STATE) {
			case "offline" -> CompletableFuture.failedFuture(new java.io.IOException("offline (QA demo)"));
			case "loading" -> new CompletableFuture<>();
			default -> CompletableFuture.supplyAsync(() -> demo.answer(uri, body), CompletableFuture.delayedExecutor(220,
				java.util.concurrent.TimeUnit.MILLISECONDS));
		};
	}

	private synchronized ProfileShareClient.Reply answer(URI uri, String body) {
		String path = uri.getPath();
		String name = path.substring(path.lastIndexOf('/') + 1);
		return switch (name) {
			case "join" -> new ProfileShareClient.Reply("signedout".equals(STATE) ? 403 : 204, "");
			case "merit_daily_claim" -> "refuse".equals(STATE) ? ok("{\"ok\":false,\"error\":\"ip_daily_limit\"}") : ok(claim().toString());
			case "flint_signin_challenge" -> ok("{\"ok\":true,\"challenge\":\"" + java.util.UUID.randomUUID() + "\",\"server_id\":\"" + "e".repeat(40) + "\"}");
			case "flint_presence_announce_v2" -> ok("{\"ok\":true,\"token\":\"" + "d".repeat(64) + "\",\"tag\":\"default\",\"ttl_seconds\":900}");
			case "flint_presence_lookup" -> ok("{}");
			case "merit_daily_status" -> ok(view(null).toString());
			default -> ok("{\"ok\":true}");
		};
	}

	private static ProfileShareClient.Reply ok(String body) {
		return new ProfileShareClient.Reply(200, body);
	}

	/** The Merit each streak day pays, as the Backend's schedule (it repeats every 7 days). */
	static final int[] WEEK = {5, 5, 10, 15, 20, 25, 40};

	private static int amount(int day) {
		return day < 1 ? 0 : WEEK[(day - 1) % WEEK.length];
	}

	private JsonObject claim() {
		long now = System.currentTimeMillis();
		if (lastClaim > 0 && now < lastClaim + 24 * HOUR) {
			JsonObject v = view(null);
			v.addProperty("ok", false);
			v.addProperty("error", "already_claimed");
			return v;
		}
		day = lastClaim > 0 && now < lastClaim + 48 * HOUR ? day + 1 : 1;
		best = Math.max(best, day);
		lastClaim = now;
		balance += amount(day);
		JsonObject claimed = new JsonObject();
		claimed.addProperty("day", day);
		claimed.addProperty("reward", amount(day));
		return view(claimed);
	}

	private JsonObject view(@Nullable JsonObject claimed) {
		long now = System.currentTimeMillis();
		boolean alive = lastClaim > 0 && now < lastClaim + 48 * HOUR;
		boolean can = lastClaim == 0 || now >= lastClaim + 24 * HOUR;
		int next = alive ? day + 1 : 1;
		int focus = can ? next : Math.max(1, next - 1);
		int first = (focus - 1) / 7 * 7 + 1;
		JsonObject v = new JsonObject();
		v.addProperty("ok", true);
		v.addProperty("balance", balance);
		v.addProperty("streak", alive ? day : 0);
		v.addProperty("best", best);
		v.addProperty("can_claim", can);
		v.addProperty("next_day", next);
		v.addProperty("next_reward", amount(next));
		if (!can) {
			v.addProperty("next_claim_at", Instant.ofEpochMilli(lastClaim + 24 * HOUR).toString());
		}
		if (alive) {
			v.addProperty("streak_ends_at", Instant.ofEpochMilli(lastClaim + 48 * HOUR).toString());
		}
		v.addProperty("server_time", Instant.ofEpochMilli(now).toString());
		v.addProperty("max_reward", 40);
		if ("paused".equals(STATE)) {
			v.addProperty("enabled", false);
		} else if ("store".equals(STATE)) {
			v.addProperty("unavailable", "store_account_required");
		}
		JsonArray track = new JsonArray();
		for (int d = first; d < first + 7; d++) {
			track.add(JsonParser.parseString("{\"day\":" + d + ",\"reward\":" + amount(d) + "}"));
		}
		v.add("track", track);
		if (claimed != null) {
			v.add("claimed", claimed);
		}
		return v;
	}
}

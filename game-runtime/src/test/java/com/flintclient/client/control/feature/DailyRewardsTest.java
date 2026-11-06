package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.FlintServices;
import com.flintclient.client.control.store.PresenceClient;
import com.flintclient.client.control.store.ProfileShareClient;
import com.flintclient.client.control.store.RewardsClient;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DailyRewardsTest {
	private static final FlintServices SERVICES = new FlintServices("https://example.supabase.co", "sb_publishable_test");
	private static final String TOKEN = "c".repeat(64);
	private static final long HOUR = 3_600_000L;
	private final FlintPresence.Account account = new FlintPresence.Account(UUID.randomUUID(), "Steve", "token");
	private final List<String> calls = new ArrayList<>();
	/** The server's clock, which runs ahead of this computer's by {@link #skew}. */
	private long skew = 7 * 60_000L;
	private long lastClaim = -1;
	private long balance = 100;
	private int day;
	private String claimError;
	/** Added to every status: the server's {@code "enabled"} and {@code "unavailable"} (none, as an older server). */
	private String unavailable = "";
	private long localNow = 1_800_000_000_000L;

	private final ProfileShareClient.Transport transport = (URI uri, Map<String, String> headers, String body) -> {
		String path = uri.getPath();
		String name = path.substring(path.lastIndexOf('/') + 1);
		calls.add(name);
		String answer = switch (name) {
			case "join" -> "";
			case "flint_signin_challenge" -> "{\"ok\":true,\"challenge\":\"6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70\",\"server_id\":\"0123456789abcdef0123456789abcdef01234567\"}";
			case "flint_presence_announce_v2" -> "{\"ok\":true,\"token\":\"" + TOKEN + "\",\"tag\":\"default\"}";
			case "merit_daily_status" -> view(false);
			case "merit_daily_claim" -> claimError != null ? "{\"ok\":false,\"error\":\"" + claimError + "\"}" : claim();
			default -> "{\"ok\":true}";
		};
		return CompletableFuture.completedFuture(new ProfileShareClient.Reply(name.equals("join") ? 204 : 200, answer));
	};
	private final PresenceClient presenceClient = new PresenceClient(SERVICES, transport);
	private final FlintPresence presence = new FlintPresence(presenceClient);
	private final DailyRewards rewards = new DailyRewards(new RewardsClient(presenceClient), presence);

	@AfterEach
	void forget() {
		FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
	}

	private long serverNow() {
		return localNow + skew;
	}

	private String claim() {
		if (lastClaim >= 0 && serverNow() < lastClaim + 24 * HOUR) {
			return view(false).replace("\"ok\":true", "\"ok\":false,\"error\":\"already_claimed\"");
		}
		day = lastClaim >= 0 && serverNow() < lastClaim + 48 * HOUR ? day + 1 : 1;
		lastClaim = serverNow();
		int reward = day <= 2 ? 5 : Math.min(60, 5 * (day - 1));
		balance += reward;
		return view(true);
	}

	private String view(boolean claimed) {
		boolean open = unavailable.isEmpty() || unavailable.endsWith("null");
		boolean can = open && (lastClaim < 0 || serverNow() >= lastClaim + 24 * HOUR);
		int next = lastClaim >= 0 && serverNow() < lastClaim + 48 * HOUR ? day + 1 : 1;
		String claimedPart = claimed ? ",\"claimed\":{\"day\":" + day + ",\"reward\":" + (day <= 2 ? 5 : Math.min(60, 5 * (day - 1))) + "}" : "";
		return "{\"ok\":true,\"balance\":" + balance + ",\"streak\":" + (lastClaim >= 0 ? day : 0) + ",\"best\":" + day + ",\"can_claim\":" + can
			+ ",\"next_day\":" + next + ",\"next_reward\":5" + (can || !open ? "" : ",\"next_claim_at\":\"" + Instant.ofEpochMilli(lastClaim + 24 * HOUR) + "\"")
			+ ",\"server_time\":\"" + Instant.ofEpochMilli(serverNow()) + "\",\"max_reward\":60,\"track\":[{\"day\":1,\"reward\":5},{\"day\":2,\"reward\":5}]"
			+ claimedPart + unavailable + "}";
	}

	/** One second of the game: presence, then the rewards, twice so answers are taken. */
	private void second() {
		localNow += 1000;
		for (int i = 0; i < 2; i++) {
			presence.tick(localNow * 1_000_000L, account, true, false, List.of());
			rewards.update(localNow);
		}
	}

	@Test
	void switchedOffOrWithoutAStoreSignInTheRewardsSaySoInsteadOfACountdown() {
		unavailable = ",\"enabled\":false,\"unavailable\":\"disabled\"";
		second();
		second();
		assertEquals(DailyRewards.State.PAUSED, rewards.state());
		assertNull(rewards.rewards());
		assertFalse(rewards.claimable(localNow));
		assertEquals(0, rewards.untilNext(localNow));
		rewards.claim(localNow);
		second();
		assertFalse(calls.contains("merit_daily_claim"), "nothing to claim");

		unavailable = ",\"enabled\":true,\"unavailable\":\"store_account_required\"";
		localNow += DailyRewards.REFRESH_MILLIS;
		second();
		assertEquals(DailyRewards.State.STORE_ACCOUNT, rewards.state(), "read again by itself");

		unavailable = ",\"enabled\":true,\"unavailable\":null";
		localNow += DailyRewards.REFRESH_MILLIS;
		second();
		assertEquals(DailyRewards.State.READY, rewards.state(), "switched on: collectable");
		assertTrue(rewards.claimable(localNow));
	}

	@Test
	void readsTheRewardsOnceTheAccountIsProven() {
		assertEquals(DailyRewards.State.LOADING, rewards.state());
		second();
		second();
		assertEquals(DailyRewards.State.READY, rewards.state());
		assertEquals(100, rewards.rewards().balance());
		assertTrue(rewards.claimable(localNow));
	}

	@Test
	void aClaimCountsOnceAndWaitsForTheServersDay() {
		second();
		second();
		rewards.claim(localNow);
		rewards.claim(localNow);
		second();
		assertEquals(1, rewards.claims(), "one claim, however many clicks");
		assertEquals(5, rewards.claimedReward());
		assertEquals(100, rewards.balanceBefore());
		assertEquals(105, rewards.rewards().balance());
		assertFalse(rewards.claimable(localNow));
		assertEquals(24 * HOUR - 1000, rewards.untilNext(localNow), 1500);
		// The computer's clock is 7 minutes behind the server's: the reward comes 24 hours after the claim by the server's
		// clock (read through the measured difference), not 7 minutes late.
		localNow += 24 * HOUR - 2000;
		assertFalse(rewards.claimable(localNow));
		localNow += 4000;
		assertTrue(rewards.claimable(localNow));
	}

	@Test
	void anExpiredProofIsRenewedAndReadAgain() {
		second();
		second();
		calls.clear();
		claimError = "expired";
		rewards.claim(localNow);
		second();
		second();
		assertTrue(calls.containsAll(List.of("merit_daily_claim", "flint_signin_challenge", "join", "flint_presence_announce_v2")), calls.toString());
		assertEquals(0, rewards.claims());
	}

	@Test
	void aClaimRefusedForTheNetworksDailyLimitSaysWhyAndIsntOfferedAgainUntilTheReset() {
		second();
		second();
		claimError = "ip_daily_limit";
		rewards.claim(localNow);
		second();
		assertEquals(DailyRewards.State.READY, rewards.state(), "a refusal isn't a lost connection");
		assertEquals("ip_daily_limit", rewards.refusal(localNow));
		assertFalse(rewards.claimable(localNow), "not offered again for clicking into the same refusal");
		long calls0 = calls.stream().filter("merit_daily_claim"::equals).count();
		rewards.claim(localNow);
		second();
		assertEquals(calls0, calls.stream().filter("merit_daily_claim"::equals).count(), "no second claim is sent");
		// After the server's UTC midnight it may be claimed again.
		long server = serverNow();
		localNow += (Math.floorDiv(server, 24 * HOUR) + 1) * 24 * HOUR - server + 1000;
		claimError = null;
		assertNull(rewards.refusal(localNow));
		assertTrue(rewards.claimable(localNow));
	}

	@Test
	void anUnexplainedRefusalWaitsTenMinutesNotAllDay() {
		second();
		second();
		claimError = "something_new";
		rewards.claim(localNow);
		second();
		assertEquals("something_new", rewards.refusal(localNow));
		localNow += DailyRewards.REFUSED_MILLIS + 1000;
		claimError = null;
		assertNull(rewards.refusal(localNow));
	}

	@Test
	void noServicesMeansUnavailable() {
		DailyRewards none = new DailyRewards(new RewardsClient(new PresenceClient(null, transport)), new FlintPresence(new PresenceClient(null, transport)));
		none.update(localNow);
		assertEquals(DailyRewards.State.UNAVAILABLE, none.state());
		assertNull(none.rewards());
		assertFalse(none.claimable(localNow));
	}

	@Test
	void anOfflineAccountCannotCollect() {
		// Mojang refuses the account (an offline one); Flint's services hand out the challenge as usual.
		ProfileShareClient.Transport refusing = (uri, headers, body) -> CompletableFuture.completedFuture(uri.getPath().endsWith("flint_signin_challenge")
			? new ProfileShareClient.Reply(200, "{\"ok\":true,\"challenge\":\"6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70\",\"server_id\":\"0123456789abcdef0123456789abcdef01234567\"}")
			: new ProfileShareClient.Reply(403, ""));
		PresenceClient client = new PresenceClient(SERVICES, refusing);
		FlintPresence p = new FlintPresence(client);
		DailyRewards r = new DailyRewards(new RewardsClient(client), p);
		p.tick(0, account, true, false, List.of());
		p.tick(1, account, true, false, List.of());
		assertEquals(DailyRewards.State.NO_ACCOUNT, r.state());
	}
}

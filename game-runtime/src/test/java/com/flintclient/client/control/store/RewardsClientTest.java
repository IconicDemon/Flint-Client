package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RewardsClientTest {
	private static RewardsClient.Answer read(String body) {
		return RewardsClient.read(new ProfileShareClient.Reply(200, body));
	}

	@Test
	void aStatusIsRead() {
		RewardsClient.Answer a = read("{\"ok\":true,\"balance\":1250,\"streak\":3,\"best\":9,\"can_claim\":false,\"next_day\":4,\"next_reward\":15,"
			+ "\"next_claim_at\":\"2026-10-29T10:00:00.123456+00:00\",\"streak_ends_at\":\"2026-10-30T10:00:00+00:00\","
			+ "\"server_time\":\"2026-10-28T22:00:00Z\",\"max_reward\":60,\"track\":[{\"day\":1,\"reward\":5},{\"day\":2,\"reward\":5},{\"day\":3,\"reward\":10}]}");
		assertEquals(PresenceClient.Status.OK, a.status());
		RewardsClient.Rewards r = a.rewards();
		assertEquals(1250, r.balance());
		assertEquals(3, r.streak());
		assertEquals(9, r.best());
		assertEquals(4, r.nextDay());
		assertEquals(15, r.nextReward());
		assertEquals(Instant.parse("2026-10-29T10:00:00.123Z").toEpochMilli(), r.nextClaimAt());
		assertEquals(Instant.parse("2026-10-28T22:00:00Z").toEpochMilli(), r.serverTime());
		assertEquals(3, r.track().size());
		assertEquals(new RewardsClient.Day(3, 10), r.track().get(2));
		assertEquals(0, r.claimedReward());
	}

	@Test
	void aClaimSaysWhatItGave() {
		RewardsClient.Rewards r = read("{\"ok\":true,\"balance\":20,\"streak\":4,\"best\":4,\"can_claim\":false,\"next_day\":5,\"next_reward\":20,"
			+ "\"server_time\":\"2026-10-28T22:00:00Z\",\"track\":[],\"claimed\":{\"day\":4,\"reward\":15}}").rewards();
		assertEquals(4, r.claimedDay());
		assertEquals(15, r.claimedReward());
		assertEquals(-1, r.nextClaimAt(), "no time given: none");
	}

	@Test
	void alreadyClaimedStillCarriesTheRewards() {
		RewardsClient.Answer a = read("{\"ok\":false,\"error\":\"already_claimed\",\"balance\":5,\"streak\":1,\"best\":1,\"can_claim\":false,"
			+ "\"next_day\":2,\"next_reward\":5,\"track\":[]}");
		assertEquals(PresenceClient.Status.OK, a.status());
		assertEquals(5, a.rewards().balance());
		assertEquals(0, a.rewards().claimedReward());
	}

	@Test
	void whyRewardsCantBeCollectedIsRead() {
		String status = "\"ok\":true,\"balance\":500,\"streak\":0,\"best\":0,\"can_claim\":false,\"next_day\":1,\"next_reward\":5,\"track\":[]";
		assertNull(read("{" + status + ",\"enabled\":true,\"unavailable\":null}").rewards().unavailable());
		assertNull(read("{" + status + "}").rewards().unavailable(), "an older server says nothing: available");
		assertEquals("disabled", read("{" + status + ",\"enabled\":false,\"unavailable\":\"disabled\"}").rewards().unavailable());
		assertEquals("disabled", read("{" + status + ",\"enabled\":false,\"unavailable\":\"store_account_required\"}").rewards().unavailable(),
			"switched off comes first");
		assertEquals("store_account_required", read("{" + status + ",\"enabled\":true,\"unavailable\":\"store_account_required\"}").rewards().unavailable());
		assertEquals("disabled", read("{" + status + ",\"enabled\":true,\"unavailable\":\"<b>odd</b>\"}").rewards().unavailable());
		assertEquals(500, read("{" + status + ",\"enabled\":false}").rewards().balance(), "the Merit balance is still shown");
	}

	@Test
	void errorsAndNonsenseAreNotRewards() {
		assertEquals(PresenceClient.Status.EXPIRED, read("{\"ok\":false,\"error\":\"expired\"}").status());
		assertEquals(PresenceClient.Status.BUSY, read("{\"ok\":false,\"error\":\"rate_limited\"}").status());
		assertEquals(PresenceClient.Status.FAILED, read("{\"ok\":true,\"balance\":-5,\"next_day\":1}").status());
		assertEquals(PresenceClient.Status.FAILED, read("[]").status());
		assertNull(read("{\"ok\":true}").rewards());
		assertEquals(PresenceClient.Status.FAILED, RewardsClient.read(new ProfileShareClient.Reply(500, "")).status());
	}
}

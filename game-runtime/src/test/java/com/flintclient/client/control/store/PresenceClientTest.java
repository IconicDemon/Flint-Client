package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PresenceClientTest {
	private static final FlintServices SERVICES = new FlintServices("https://example.supabase.co", "sb_publishable_test");
	private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final String TOKEN = "a".repeat(64);

	/** Answers by address, keeping every request. */
	private static final class Fake implements ProfileShareClient.Transport {
		final List<String> uris = new ArrayList<>();
		final List<String> bodies = new ArrayList<>();
		final List<Map<String, String>> headers = new ArrayList<>();
		final Function<String, CompletableFuture<ProfileShareClient.Reply>> answer;

		Fake(Function<String, CompletableFuture<ProfileShareClient.Reply>> answer) {
			this.answer = answer;
		}

		@Override
		public CompletableFuture<ProfileShareClient.Reply> post(URI uri, Map<String, String> headers, String body) {
			uris.add(uri.toString());
			bodies.add(body);
			this.headers.add(headers);
			return answer.apply(uri.toString());
		}
	}

	private static CompletableFuture<ProfileShareClient.Reply> reply(int status, String body) {
		return CompletableFuture.completedFuture(new ProfileShareClient.Reply(status, body));
	}

	private static final String CHALLENGE = "6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70";
	private static final String SERVER_ID = "0123456789abcdef0123456789abcdef01234567";

	/** Flint's services answer a challenge, then a token; Mojang answers {@code mojang}. */
	private static Fake services(int mojang) {
		return new Fake(uri -> uri.contains("mojang") ? reply(mojang, mojang == 403 ? "{\"error\":\"ForbiddenOperationException\"}" : "")
			: uri.endsWith("flint_signin_challenge") ? reply(200, "{\"ok\":true,\"challenge\":\"" + CHALLENGE + "\",\"server_id\":\"" + SERVER_ID + "\"}")
			: reply(200, "{\"ok\":true,\"token\":\"" + TOKEN + "\",\"tag\":\"staff\",\"ttl_seconds\":900}"));
	}

	@Test
	void anAnnounceTakesAChallengeJoinsItsServerIdAtMojangThenAnnouncesWithTheChallenge() {
		Fake fake = services(204);
		PresenceClient.Announced a = new PresenceClient(SERVICES, fake).announce(STEVE, "Steve", "mc-access-token", true).join();
		assertEquals(PresenceClient.Status.OK, a.status());
		assertEquals(TOKEN, a.token());
		assertEquals("staff", a.tag());
		assertEquals("https://example.supabase.co/rest/v1/rpc/flint_signin_challenge", fake.uris.get(0));
		assertEquals("presence", JsonParser.parseString(fake.bodies.get(0)).getAsJsonObject().get("p_purpose").getAsString());
		assertEquals(PresenceClient.MOJANG_JOIN.toString(), fake.uris.get(1));
		JsonObject join = JsonParser.parseString(fake.bodies.get(1)).getAsJsonObject();
		assertEquals(SERVER_ID, join.get("serverId").getAsString(), "the server id Flint's services picked, never one the game picks");
		assertEquals("069a79f444e94726a5befca90e38aaf5", join.get("selectedProfile").getAsString());
		assertEquals("mc-access-token", join.get("accessToken").getAsString());
		assertFalse(fake.headers.get(1).containsKey("apikey"), "Flint's key never goes to Mojang");
		assertEquals("https://example.supabase.co/rest/v1/rpc/flint_presence_announce_v2", fake.uris.get(2));
		JsonObject announce = JsonParser.parseString(fake.bodies.get(2)).getAsJsonObject();
		assertEquals(CHALLENGE, announce.get("p_challenge").getAsString());
		assertFalse(announce.has("p_server_id"));
		assertFalse(fake.bodies.get(2).contains("mc-access-token"), "the access token goes to Mojang only");
		assertFalse(fake.uris.stream().anyMatch(u -> u.endsWith("/flint_presence_announce")), "the locked-down announce is never called");
	}

	@Test
	void anAccountMojangRefusesIsUnverifiedAndNeverAnnounced() {
		Fake fake = services(403);
		assertEquals(PresenceClient.Status.UNVERIFIED, new PresenceClient(SERVICES, fake).announce(STEVE, "Steve", "0", true).join().status());
		assertEquals(2, fake.uris.size(), "a challenge and Mojang's refusal, no announce");
		Fake none = services(204);
		assertEquals(PresenceClient.Status.UNVERIFIED, new PresenceClient(SERVICES, none).announce(STEVE, "not a name", "t", true).join().status());
		assertEquals(0, none.uris.size(), "a name Mojang can't have isn't sent anywhere");
	}

	@Test
	void aBusyMojangOrServicesIsBusyAndAMissingChallengeFunctionIsAFailure() {
		assertEquals(PresenceClient.Status.BUSY, new PresenceClient(SERVICES, services(503)).announce(STEVE, "Steve", "t", true).join().status());
		assertEquals(PresenceClient.Status.BUSY, new PresenceClient(SERVICES, services(429)).announce(STEVE, "Steve", "t", true).join().status());
		Fake limited = new Fake(uri -> reply(200, "{\"ok\":false,\"error\":\"rate_limited\"}"));
		assertEquals(PresenceClient.Status.BUSY, new PresenceClient(SERVICES, limited).announce(STEVE, "Steve", "t", true).join().status());
		assertEquals(1, limited.uris.size(), "Mojang isn't asked without a challenge");
		Fake missing = new Fake(uri -> reply(404, "{\"code\":\"PGRST202\"}"));
		assertEquals(PresenceClient.Status.FAILED, new PresenceClient(SERVICES, missing).announce(STEVE, "Steve", "t", true).join().status());
		assertNull(PresenceClient.readChallenge(new ProfileShareClient.Reply(200, "{\"ok\":true,\"challenge\":\"x\",\"server_id\":\"" + SERVER_ID + "\"}")));
	}
	@Test
	void noConnectionIsOfflineAndNoServicesIsNotConfigured() {
		Fake fake = new Fake(uri -> CompletableFuture.failedFuture(new IOException("no route")));
		PresenceClient client = new PresenceClient(SERVICES, fake);
		assertEquals(PresenceClient.Status.OFFLINE, client.announce(STEVE, "Steve", "t", true).join().status());
		assertEquals(PresenceClient.Status.OFFLINE, client.heartbeat(STEVE, TOKEN, true).join().status());
		assertEquals(PresenceClient.Status.OFFLINE, client.lookup(List.of(STEVE)).join().status());
		PresenceClient none = new PresenceClient(null, fake);
		assertFalse(none.configured());
		assertEquals(PresenceClient.Status.NOT_CONFIGURED, none.announce(STEVE, "Steve", "t", true).join().status());
		assertEquals(PresenceClient.Status.NOT_CONFIGURED, none.heartbeat(STEVE, TOKEN, true).join().status());
		assertEquals(PresenceClient.Status.NOT_CONFIGURED, none.lookup(List.of(STEVE)).join().status());
	}

	@Test
	void heartbeatAnswersAreRead() {
		assertEquals(PresenceClient.Status.OK, PresenceClient.readStatus(new ProfileShareClient.Reply(200, "{\"ok\":true}")));
		assertEquals(PresenceClient.Status.EXPIRED,
			PresenceClient.readStatus(new ProfileShareClient.Reply(200, "{\"ok\":false,\"error\":\"expired\"}")));
		assertEquals(PresenceClient.Status.BUSY,
			PresenceClient.readStatus(new ProfileShareClient.Reply(200, "{\"ok\":false,\"error\":\"rate_limited\"}")));
		assertEquals(PresenceClient.Status.FAILED, PresenceClient.readStatus(new ProfileShareClient.Reply(500, "oops")));
	}

	@Test
	void aHeartbeatCarriesTheOwnMarkWhenTheServerSaysIt() {
		PresenceClient.Beat staff = PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":\"staff\"}"));
		assertEquals(PresenceClient.Status.OK, staff.status());
		assertEquals("staff", staff.tag());
		assertEquals("default", PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":\"default\"}")).tag(),
			"a mark taken away comes back as the default");
		assertEquals("co_owner", PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":\"CO_OWNER\"}")).tag());
		assertNull(PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true}")).tag(), "an older server: the mark stays");
		assertNull(PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":\"§k<b>\"}")).tag(), "not a mark name");
		assertNull(PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":7}")).tag());
		PresenceClient.Beat expired = PresenceClient.readBeat(new ProfileShareClient.Reply(200, "{\"ok\":false,\"error\":\"expired\",\"tag\":\"owner\"}"));
		assertEquals(PresenceClient.Status.EXPIRED, expired.status());
		assertNull(expired.tag(), "a refused heartbeat says nothing about marks");
	}

	@Test
	void aLookupKeepsOnlyUuidsWithAMarkName() {
		UUID alex = UUID.randomUUID();
		PresenceClient.Found found = PresenceClient.readFound(new ProfileShareClient.Reply(200,
			"{\"" + STEVE + "\":\"default\",\"" + alex + "\":\"content_creator\",\"nope\":\"default\",\"" + UUID.randomUUID() + "\":5}"));
		assertEquals(PresenceClient.Status.OK, found.status());
		assertEquals(Map.of(STEVE, "default", alex, "content_creator"), found.players());
		assertEquals(PresenceClient.Status.BUSY,
			PresenceClient.readFound(new ProfileShareClient.Reply(400, "{\"message\":\"rate_limited\"}")).status());
	}

	@Test
	void aLookupAsksAboutAtMost200Players() {
		Fake fake = new Fake(uri -> reply(200, "{}"));
		List<UUID> crowd = new ArrayList<>();
		for (int i = 0; i < 250; i++) {
			crowd.add(UUID.randomUUID());
		}
		new PresenceClient(SERVICES, fake).lookup(crowd).join();
		assertEquals(PresenceClient.MAX_LOOKUP, JsonParser.parseString(fake.bodies.get(0)).getAsJsonObject().getAsJsonArray("p_uuids").size());
	}

	@Test
	void aBadTokenInAnAnswerIsAFailure() {
		PresenceClient.Announced a = PresenceClient.readAnnounced(new ProfileShareClient.Reply(200, "{\"ok\":true,\"token\":\"short\"}"));
		assertEquals(PresenceClient.Status.FAILED, a.status());
		assertNull(a.token());
	}

	@Test
	void onlyServicesOnThisComputerAreAskedAboutAnyone() {
		Fake fake = new Fake(uri -> reply(200, "{}"));
		assertFalse(new PresenceClient(SERVICES, fake).asksAnyone(), "Flint's servers know Mojang accounts only");
		assertTrue(new PresenceClient(new FlintServices("http://127.0.0.1:8788", "flint-qa"), fake).asksAnyone());
		assertTrue(new PresenceClient(new FlintServices("http://localhost:54321", "flint-qa"), fake).asksAnyone());
		assertFalse(new PresenceClient(null, fake).asksAnyone());
		// The lockdown's answer to the old sign-in is a failure (retried with back-off), never a refusal of the account.
		assertEquals(PresenceClient.Status.FAILED, PresenceClient.error("update_required"));
	}
}

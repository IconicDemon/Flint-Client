package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.store.FlintServices;
import com.flintclient.client.control.store.PresenceClient;
import com.flintclient.client.control.store.ProfileShareClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlintPresenceTest {
	private static final FlintServices SERVICES = new FlintServices("https://example.supabase.co", "sb_publishable_test");
	private static final String TOKEN = "b".repeat(64);
	private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
	private static final String CHALLENGE = "{\"ok\":true,\"challenge\":\"6f1c2e0a-8f4b-4d55-9a7e-2b3c4d5e6f70\",\"server_id\":\"0123456789abcdef0123456789abcdef01234567\"}";
	private final UUID me = UUID.randomUUID();
	private final UUID friend = UUID.randomUUID();
	private final UUID stranger = UUID.randomUUID();
	private final FlintPresence.Account account = new FlintPresence.Account(me, "Steve", "token");

	/** Flint's server and Mojang's, as far as presence needs them: which calls were made, in order. */
	private final List<String> calls = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();
	private String friendTag = "default";
	private int mojangStatus = 204;
	private String beatAnswer = "{\"ok\":true}";
	private String announceTag = "default";
	/** When set, heartbeats wait here for the test to answer them (an answer still on its way). */
	private List<CompletableFuture<ProfileShareClient.Reply>> heldBeats;
	/** When set, lookups wait here for the test to answer them. */
	private List<CompletableFuture<ProfileShareClient.Reply>> heldLookups;
	/** The services' answers to a challenge and to a lookup (production before Backend 6500 has no challenge function: 404). */
	private int challengeStatus = 200;
	private String challengeAnswer = CHALLENGE;
	private String announceAnswer;
	private int lookupStatus = 200;
	private final ProfileShareClient.Transport transport = (URI uri, Map<String, String> headers, String body) -> {
		String path = uri.getPath();
		String name = path.substring(path.lastIndexOf('/') + 1);
		calls.add(name);
		bodies.add(body);
		if (name.equals("flint_presence_heartbeat") && heldBeats != null) {
			CompletableFuture<ProfileShareClient.Reply> held = new CompletableFuture<>();
			heldBeats.add(held);
			return held;
		}
		if (name.equals("flint_presence_lookup") && heldLookups != null) {
			CompletableFuture<ProfileShareClient.Reply> held = new CompletableFuture<>();
			heldLookups.add(held);
			return held;
		}
		String answer = switch (name) {
			case "join" -> "";
			case "flint_signin_challenge" -> challengeAnswer;
			case "flint_presence_announce_v2" -> announceAnswer != null ? announceAnswer
				: "{\"ok\":true,\"token\":\"" + TOKEN + "\",\"tag\":\"" + announceTag + "\"}";
			case "flint_presence_heartbeat" -> beatAnswer;
			case "flint_presence_lookup" -> body.contains(friend.toString()) ? "{\"" + friend + "\":\"" + friendTag + "\"}" : "{}";
			default -> "{\"ok\":true}";
		};
		int status = switch (name) {
			case "join" -> mojangStatus;
			case "flint_signin_challenge" -> challengeStatus;
			case "flint_presence_lookup" -> lookupStatus;
			default -> 200;
		};
		return CompletableFuture.completedFuture(new ProfileShareClient.Reply(status, answer));
	};
	private final FlintPresence presence = new FlintPresence(new PresenceClient(SERVICES, transport));

	@AfterEach
	void forget() {
		FlintPlayers.clear();
		FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
	}

	@Test
	void announcesThenShowsTheFlintPlayersInTheList() {
		presence.tick(0, account, true, true, List.of(friend, stranger));
		assertEquals(List.of("flint_signin_challenge", "join", "flint_presence_announce_v2", "flint_presence_lookup"), calls);
		assertNull(FlintPlayers.tag(friend), "answers are taken on the next tick, on the game's thread");
		presence.tick(SECOND, account, true, true, List.of(friend, stranger));
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend));
		assertNull(FlintPlayers.tag(stranger));
	}

	@Test
	void heartbeatsEveryFiveMinutesAndAnnouncesAgainWhenTheTokenExpired() {
		presence.tick(0, account, true, false, List.of());
		presence.tick(SECOND, account, true, false, List.of());
		calls.clear();
		presence.tick(FlintPresence.HEARTBEAT_NANOS, account, true, false, List.of());
		assertEquals(List.of(), calls, "not yet");
		presence.tick(FlintPresence.HEARTBEAT_NANOS + SECOND, account, true, false, List.of());
		assertEquals(List.of("flint_presence_heartbeat"), calls);
		// Its answer is taken on the next tick, five minutes before the next beat.
		presence.tick(FlintPresence.HEARTBEAT_NANOS + 2 * SECOND, account, true, false, List.of());
		beatAnswer = "{\"ok\":false,\"error\":\"expired\"}";
		presence.tick(2 * FlintPresence.HEARTBEAT_NANOS + 2 * SECOND, account, true, false, List.of());
		presence.tick(2 * FlintPresence.HEARTBEAT_NANOS + 3 * SECOND, account, true, false, List.of());
		assertEquals(List.of("flint_presence_heartbeat", "flint_presence_heartbeat", "flint_signin_challenge", "join", "flint_presence_announce_v2"), calls);
	}

	@Test
	void sharingOffStillProvesTheAccountButHidesIt() {
		presence.tick(0, account, false, false, List.of());
		assertEquals(List.of("flint_signin_challenge", "join", "flint_presence_announce_v2"), calls);
		assertTrue(bodies.get(2).contains("\"p_visible\":false"), "hidden from the start");
		presence.tick(SECOND, account, false, false, List.of());
		assertEquals(TOKEN, presence.token(), "the proof is kept for daily rewards");
		assertEquals(FlintPresence.Proof.PROVEN, presence.proof());
		presence.tick(2 * SECOND, account, true, false, List.of());
		assertEquals(List.of("flint_signin_challenge", "join", "flint_presence_announce_v2", "flint_presence_heartbeat"), calls, "turned on: told at once");
		assertTrue(bodies.get(3).contains("\"p_visible\":true"));
	}

	@Test
	void anotherAccountLeavesAsTheFirstAndAnnouncesAsTheSecond() {
		presence.tick(0, account, true, false, List.of());
		presence.tick(SECOND, new FlintPresence.Account(UUID.randomUUID(), "Alex", "t2"), true, false, List.of());
		assertEquals(List.of("flint_signin_challenge", "join", "flint_presence_announce_v2", "flint_presence_leave", "flint_signin_challenge", "join", "flint_presence_announce_v2"), calls);
	}

	@Test
	void anAccountMojangRefusesIsNotAskedAgain() {
		mojangStatus = 403;
		presence.tick(0, account, true, false, List.of());
		presence.tick(TimeUnit.HOURS.toNanos(2), account, true, false, List.of());
		assertEquals(List.of("flint_signin_challenge", "join"), calls, "one challenge and Mojang's refusal, never again");
	}

	@Test
	void yourOwnMarkFollowsTheHeartbeatWithoutARestart() {
		presence.tick(0, account, true, false, List.of());
		presence.tick(SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
		// Flint gives the account a mark while it plays: the next heartbeat brings it.
		beatAnswer = "{\"ok\":true,\"tag\":\"staff\"}";
		presence.tick(FlintPresence.HEARTBEAT_NANOS + SECOND, account, true, false, List.of());
		presence.tick(FlintPresence.HEARTBEAT_NANOS + 2 * SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.own());
		// An older server says nothing about marks: the mark stays.
		beatAnswer = "{\"ok\":true}";
		presence.tick(2 * FlintPresence.HEARTBEAT_NANOS + 2 * SECOND, account, true, false, List.of());
		presence.tick(2 * FlintPresence.HEARTBEAT_NANOS + 3 * SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.own());
		// Taken away: back to the default. A mark name this game doesn't know is the default too.
		beatAnswer = "{\"ok\":true,\"tag\":\"default\"}";
		presence.tick(3 * FlintPresence.HEARTBEAT_NANOS + 3 * SECOND, account, true, false, List.of());
		presence.tick(3 * FlintPresence.HEARTBEAT_NANOS + 4 * SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
		beatAnswer = "{\"ok\":true,\"tag\":\"champion\"}";
		FlintPlayers.setOwn(FlintPlayers.Tag.OWNER);
		presence.tick(4 * FlintPresence.HEARTBEAT_NANOS + 4 * SECOND, account, true, false, List.of());
		presence.tick(4 * FlintPresence.HEARTBEAT_NANOS + 5 * SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
	}

	@Test
	void anotherAccountNeverWearsThePreviousAccountsMark() {
		announceTag = "staff";
		presence.tick(0, account, true, false, List.of());
		presence.tick(SECOND, account, true, false, List.of());
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.own());
		// A heartbeat for Steve is on its way when the game switches to Alex.
		heldBeats = new ArrayList<>();
		presence.tick(FlintPresence.HEARTBEAT_NANOS + SECOND, account, true, false, List.of());
		assertEquals(1, heldBeats.size());
		announceTag = "default";
		FlintPresence.Account alex = new FlintPresence.Account(UUID.randomUUID(), "Alex", "t2");
		presence.tick(FlintPresence.HEARTBEAT_NANOS + 2 * SECOND, alex, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own(), "Steve's mark leaves with Steve");
		presence.tick(FlintPresence.HEARTBEAT_NANOS + 3 * SECOND, alex, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
		// Steve's late answer says "owner": it's Steve's, never Alex's.
		heldBeats.get(0).complete(new ProfileShareClient.Reply(200, "{\"ok\":true,\"tag\":\"owner\"}"));
		presence.tick(FlintPresence.HEARTBEAT_NANOS + 4 * SECOND, alex, true, false, List.of());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
		assertEquals(TOKEN, presence.token(), "Alex stays proven");
	}

	@Test
	void marksComeFromTheServerAndLeaveWithTheList() {
		friendTag = "content_creator";
		presence.tick(0, null, true, true, List.of(friend));
		presence.tick(SECOND, null, true, true, List.of(friend));
		assertEquals(FlintPlayers.Tag.CONTENT_CREATOR, FlintPlayers.tag(friend));
		presence.tick(2 * SECOND, null, true, true, List.of());
		assertNull(FlintPlayers.tag(friend), "nobody listed (left the server): nobody known");
		presence.tick(3 * SECOND, null, true, false, List.of(friend));
		assertEquals(List.of("flint_presence_lookup"), calls, "marks off: nobody is asked about");
	}

	@Test
	void playersLeavingNeedNoLookupButSomeoneNewIsAskedAboutWithinFiveSeconds() {
		UUID newcomer = UUID.randomUUID();
		presence.tick(0, null, true, true, List.of(stranger, friend), 1);
		presence.tick(SECOND, null, true, true, List.of(stranger, friend), 1);
		assertEquals(1, calls.size());
		// The players drawn around you change as they come and go: someone leaving asks nothing.
		presence.tick(10 * SECOND, null, true, true, List.of(friend), 1);
		presence.tick(20 * SECOND, null, true, true, List.of(stranger), 1);
		assertEquals(1, calls.size(), "nobody new: the minute's lookup will do");
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend), "still known until the next answer");
		presence.tick(21 * SECOND, null, true, true, List.of(stranger, newcomer), 1);
		assertEquals(2, calls.size(), "someone new is asked about at once");
		assertTrue(bodies.get(1).contains(newcomer.toString()) && bodies.get(1).contains(stranger.toString()), "with everyone around");
	}

	@Test
	void anotherServerForgetsWhoWasKnownAndNeverTakesTheLastServersAnswer() {
		presence.tick(0, null, true, true, List.of(friend), 1);
		presence.tick(SECOND, null, true, true, List.of(friend), 1);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend));
		// Another server: the marks go at once, before any answer.
		presence.tick(2 * SECOND, null, true, true, List.of(stranger), 2);
		assertNull(FlintPlayers.tag(friend));
		// A lookup asked on server 2 is still on its way when the game moves to server 3.
		heldLookups = new ArrayList<>();
		presence.tick(7 * SECOND, null, true, true, List.of(friend, stranger), 2);
		assertEquals(1, heldLookups.size());
		presence.tick(8 * SECOND, null, true, true, List.of(stranger), 3);
		heldLookups.get(0).complete(new ProfileShareClient.Reply(200, "{\"" + friend + "\":\"owner\"}"));
		presence.tick(13 * SECOND, null, true, true, List.of(stranger), 3);
		assertNull(FlintPlayers.tag(friend), "server 2's answer is never server 3's");
		assertEquals(2, heldLookups.size(), "server 3's players are asked about once the old answer is in");
		String asked = bodies.get(bodies.size() - 1);
		assertTrue(asked.contains(stranger.toString()) && !asked.contains(friend.toString()));
		// Leaving the server (no connection): nobody is known.
		heldLookups.get(1).complete(new ProfileShareClient.Reply(200, "{\"" + stranger + "\":\"staff\"}"));
		presence.tick(14 * SECOND, null, true, true, List.of(stranger), 3);
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.tag(stranger));
		presence.tick(15 * SECOND, null, true, true, List.of(), 0);
		assertNull(FlintPlayers.tag(stranger));
	}

	@Test
	void failingLookupsWaitForTheirBackOffWhoeverComes() {
		lookupStatus = 429;
		presence.tick(0, null, true, true, List.of(stranger), 1);
		presence.tick(SECOND, null, true, true, List.of(stranger), 1);
		presence.tick(10 * SECOND, null, true, true, List.of(stranger, friend), 1);
		assertEquals(1, calls.size(), "limited: no lookup for newcomers until the back-off ends");
		lookupStatus = 200;
		presence.tick(SECOND + FlintPresence.RETRY_NANOS, null, true, true, List.of(stranger, friend), 1);
		assertEquals(2, calls.size());
		presence.tick(2 * SECOND + FlintPresence.RETRY_NANOS, null, true, true, List.of(stranger, friend), 1);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend));
	}

	@Test
	void servicesWithoutTheChallengeAreAskedAgainWithBackOffWhileLookupsGoOn() {
		// Production before Backend 6500: no challenge function (PostgREST answers 404).
		challengeStatus = 404;
		challengeAnswer = "{\"code\":\"PGRST202\",\"message\":\"Could not find the function public.flint_signin_challenge\"}";
		presence.tick(0, account, true, true, List.of(friend), 1);
		presence.tick(SECOND, account, true, true, List.of(friend), 1);
		assertEquals(List.of("flint_signin_challenge", "flint_presence_lookup"), calls);
		assertEquals(FlintPresence.Proof.OFFLINE, presence.proof());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend), "others' marks don't need your proof");
		// Asked again after 1, 2, then 4 minutes.
		long t = SECOND;
		for (long wait : new long[] {1, 2, 4}) {
			calls.clear();
			presence.tick(t + TimeUnit.MINUTES.toNanos(wait) - 2 * SECOND, account, true, false, List.of(), 0);
			assertEquals(List.of(), calls, "not before " + wait + " min");
			t += TimeUnit.MINUTES.toNanos(wait);
			presence.tick(t, account, true, false, List.of(), 0);
			assertEquals(List.of("flint_signin_challenge"), calls, "after " + wait + " min");
			t += SECOND;
			presence.tick(t, account, true, false, List.of(), 0);
		}
		// The old sign-in's lockdown answer (Backend 6400), should the new one ever give it, backs off the same way.
		challengeStatus = 200;
		challengeAnswer = CHALLENGE;
		announceAnswer = "{\"ok\":false,\"error\":\"update_required\"}";
		calls.clear();
		t += TimeUnit.MINUTES.toNanos(8);
		presence.tick(t, account, true, false, List.of(), 0);
		presence.tick(t + SECOND, account, true, false, List.of(), 0);
		assertEquals(List.of("flint_signin_challenge", "join", "flint_presence_announce_v2"), calls);
		assertEquals(FlintPresence.Proof.OFFLINE, presence.proof());
		// Once the services have it, the next try proves the account.
		announceAnswer = null;
		t += TimeUnit.MINUTES.toNanos(16) + SECOND;
		presence.tick(t, account, true, false, List.of(), 0);
		presence.tick(t + SECOND, account, true, false, List.of(), 0);
		assertEquals(FlintPresence.Proof.PROVEN, presence.proof());
	}

	@Test
	void theListIsAskedAboutAgainWhenItChangesButNotMoreThanEveryFiveSeconds() {
		presence.tick(0, null, true, true, List.of(stranger));
		presence.tick(SECOND, null, true, true, List.of(stranger, friend));
		assertEquals(1, calls.size());
		presence.tick(5 * SECOND, null, true, true, List.of(stranger, friend));
		assertEquals(2, calls.size());
		presence.tick(6 * SECOND, null, true, true, List.of(stranger, friend));
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.tag(friend));
		presence.tick(30 * SECOND, null, true, true, List.of(stranger, friend));
		assertEquals(2, calls.size(), "unchanged: once a minute");
		presence.tick(66 * SECOND, null, true, true, List.of(stranger, friend));
		assertEquals(3, calls.size());
	}
}

package com.flintclient.client.module.social;

import static com.flintclient.client.module.social.SocialLinkTest.ALEX;
import static com.flintclient.client.module.social.SocialLinkTest.STEVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.FriendsClient.Notice;
import com.flintclient.client.module.social.FriendsClient.Reach;
import com.flintclient.client.module.social.SocialLink.Friends;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The game's Friends state: when it asks the Flint app, what it makes of each answer, and what is news. */
class FriendsClientTest {
	private long now = 1_000_000;
	private String playing = STEVE;
	private final List<String> asked = new ArrayList<>();
	private Object next = friends("ready", "");

	/** A Friends answer for Steve with Alex as a friend ({@code extra}: more fields). */
	private static Friends friends(String phase, String extra) {
		return SocialLink.readFriends(JsonParser.parseString("{\"phase\":\"" + phase + "\",\"me\":{\"uuid\":\"" + STEVE
			+ "\",\"name\":\"Steve\",\"status\":\"online\"}" + extra + "}").getAsJsonObject(), null);
	}

	private static String alex(String status, int unread) {
		return ",\"friends\":[{\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\",\"status\":\"" + status + "\",\"unread\":" + unread + "}]";
	}

	private FriendsClient client() {
		return new FriendsClient((who, chat) -> {
			asked.add(who + (chat == null ? "" : " " + chat));
			if (next instanceof LinkException e) {
				throw e;
			}
			return (Friends) next;
		}, Runnable::run, () -> now, () -> playing);
	}

	@Test
	void withoutAGameLinkThereIsNothingToAsk() {
		FriendsClient client = new FriendsClient(null, Runnable::run, () -> now, () -> playing);
		client.watch(null);
		client.tick();
		assertEquals(Reach.NO_APP, client.reach());
		assertEquals(0, client.badge());
	}

	@Test
	void itAsksOnlyWhenSomethingWantsItEvery4SecondsOpenAnd15Otherwise() {
		FriendsClient client = client();
		client.tick();
		assertEquals(List.of(), asked, "nothing wants Friends");
		client.want();
		client.tick();
		client.tick();
		assertEquals(1, asked.size(), "one poll, answered on the next tick");
		assertEquals(Reach.READY, client.reach());
		now += 5000;
		client.want();
		client.tick();
		assertEquals(1, asked.size(), "the count alone: every 15 s");
		now += 10_000;
		client.want();
		client.tick();
		assertEquals(2, asked.size());
		client.watch(ALEX);
		client.tick();
		client.tick();
		assertEquals(STEVE + " " + ALEX, asked.get(asked.size() - 1), "opening a chat asks at once, with the chat");
		int before = asked.size();
		now += 4000;
		client.watch(ALEX);
		client.tick();
		assertEquals(before + 1, asked.size(), "every 4 s while the screen is open");
		now += 60_000;
		client.tick();
		assertEquals(before + 1, asked.size(), "nothing wants it any more");
	}

	@Test
	void eachAnswerSaysWhatThePlayerSees() {
		FriendsClient client = client();
		next = friends("signed_out", ",\"testIdentity\":true");
		client.watch(null);
		client.tick();
		client.tick();
		assertEquals(Reach.SIGNED_OUT, client.reach());
		assertTrue(client.testIdentity());
		next = new LinkException("account", false);
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.ACCOUNT, client.reach());
		next = new LinkException("bad request", false);
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.OLD_APP, client.reach(), "a Flint from before Friends in game");
		next = new LinkException("Flint isn't reachable.", true);
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.UNREACHABLE, client.reach());
		assertNull(client.friends());
		next = friends("unavailable", "");
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.UNAVAILABLE, client.reach());
		next = friends("error", ",\"error\":\"mojang\"");
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.ERROR, client.reach());
		assertEquals("mojang", client.error());
		next = friends("ready", alex("online", 2) + ",\"offline\":true");
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.READY, client.reach());
		assertTrue(client.friends().offline(), "offline: the last state, marked");
		assertEquals(2, client.badge());
		next = friends("connecting", "");
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.READY, client.reach(), "a reconnect keeps what was shown");
		next = SocialLink.readFriends(JsonParser.parseString("{\"configured\":false,\"phase\":\"unavailable\"}").getAsJsonObject(), null);
		client.refreshSoon();
		client.tick();
		client.tick();
		assertEquals(Reach.NOT_CONFIGURED, client.reach());
	}

	@Test
	void anotherAccountNeverShowsTheLastOnesFriends() {
		FriendsClient client = client();
		next = friends("ready", alex("online", 0));
		client.watch(null);
		client.tick();
		client.tick();
		assertNotNull(client.friends());
		playing = ALEX;
		client.refreshSoon();
		client.tick();
		assertNull(client.friends(), "switched: nothing of Steve's");
		assertEquals(Reach.LOADING, client.reach());
		assertEquals(ALEX, asked.get(asked.size() - 1));
	}

	@Test
	void newsIsWhatChangedNeverWhatWasAlreadyWaiting() {
		FriendsClient client = client();
		String request = ",\"incoming\":[{\"id\":4,\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}]";
		next = friends("ready", alex("offline", 1) + request);
		client.want();
		client.tick();
		client.tick();
		assertEquals(List.of(), client.takeNotices(), "the first answer is silent");
		next = friends("ready", alex("online", 3) + request);
		now += 15_000;
		client.want();
		client.tick();
		client.tick();
		List<Notice> news = client.takeNotices();
		assertEquals(List.of(Notice.Kind.ONLINE, Notice.Kind.MESSAGE), news.stream().map(Notice::kind).toList());
		assertEquals("Alex sent you 2 messages", news.get(1).text());
		assertEquals(List.of(), client.takeNotices(), "taken once");

		Friends before = friends("ready", ",\"outgoing\":[{\"id\":9,\"uuid\":\"" + ALEX + "\",\"name\":\"Alex\"}]");
		Friends after = friends("ready", alex("online", 0) + ",\"incoming\":[{\"id\":5,\"uuid\":\"" + STEVE + "\",\"name\":\"Kestrel\"}]");
		List<String> words = FriendsClient.changes(before, after).stream().map(Notice::text).toList();
		assertEquals(List.of("Kestrel wants to be friends", "Alex accepted your friend request"), words);
		assertEquals(List.of(), FriendsClient.changes(null, after));
	}

	@Test
	void actionsEndOnTheRenderThreadWithFlintsCode() {
		FriendsClient client = client();
		List<Runnable> queued = new ArrayList<>();
		FriendsClient deferred = new FriendsClient((who, chat) -> friends("ready", ""), queued::add, () -> now, () -> playing);
		AtomicReference<String> got = new AtomicReference<>();
		deferred.run(() -> "sent", got::set, code -> got.set("failed " + code));
		assertNull(got.get(), "nothing until the worker ran");
		queued.remove(0).run();
		assertNull(got.get(), "and the render thread took it");
		deferred.tick();
		assertEquals("sent", got.get());
		deferred.run(() -> {
			throw new LinkException("not_on_flint", false);
		}, got::set, code -> got.set("failed " + code));
		queued.remove(0).run();
		deferred.tick();
		assertEquals("failed not_on_flint", got.get());
		deferred.run(() -> {
			throw new LinkException("Flint isn't reachable.", true);
		}, got::set, code -> got.set("failed " + code));
		queued.remove(0).run();
		deferred.tick();
		assertEquals("failed unreachable", got.get());
		assertEquals(Reach.LOADING, client.reach());
	}
}

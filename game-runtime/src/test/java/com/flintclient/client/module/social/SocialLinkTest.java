package com.flintclient.client.module.social;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Friends;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** What Flint answers is read carefully: the launcher's shape is taken, anything else is left out. */
class SocialLinkTest {
	static final String STEVE = "8667ba71b85a4004af54457a9734eed7";
	static final String ALEX = "ec561538f3fd461daff5086b22154bce";
	static final String CAPE = "00000000-0000-4000-8000-000000000001";

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void theFriendsPagesStateIsRead() {
		Friends f = SocialLink.readFriends(json("""
			{"ok":true,"configured":true,"phase":"ready","me":{"uuid":"%s","name":"Steve","status":"busy","allowRequests":false},
			 "friends":[{"uuid":"%s","name":"Alex","status":"online","inGame":true,"lastSeen":1762300000000,"pinned":true,"unread":3}],
			 "incoming":[{"id":12,"uuid":"%s","name":"Alex","at":1}],"outgoing":[{"id":"r-9","uuid":"%s","name":"Alex"}],
			 "offline":false,"stale":false,"error":null,"syncedAt":5,
			 "chat":{"with":"%s","messages":[{"id":2,"from":"%s","to":"%s","body":"yo","sensitive":false,"at":1,"read":true},
			   {"id":1,"from":"%s","to":"%s","body":"hi","sensitive":true,"at":0,"read":false}],"loaded":true,"more":true}}
			""".formatted(STEVE, ALEX, ALEX, ALEX, ALEX, STEVE, ALEX, ALEX, STEVE)), ALEX);
		assertTrue(f.ready());
		assertEquals("busy", f.me().status());
		assertFalse(f.me().allowRequests());
		assertEquals(1, f.friends().size());
		assertTrue(f.friends().get(0).inGame() && f.friends().get(0).pinned());
		assertEquals(3 + 1, f.badge(), "unread and requests waiting");
		assertEquals(new JsonPrimitive(12L), f.incoming().get(0).id());
		assertEquals("r-9", f.outgoing().get(0).key());
		assertEquals(2, f.chat().messages().size());
		assertEquals(1, f.chat().messages().get(0).id(), "oldest first");
		assertTrue(f.chat().messages().get(0).sensitive());
		assertTrue(f.chat().loaded() && f.chat().more());
	}

	@Test
	void anythingMalformedIsLeftOut() {
		Friends f = SocialLink.readFriends(json("""
			{"phase":"party","friends":[{"uuid":"nope","name":"X"},{"uuid":"%s","name":"  "},{"uuid":"%s","name":"Al\\u0000ex\\u202E","status":"dancing","unread":-4},
			 "string",{"uuid":"%s"}],
			 "incoming":[{"id":{"x":1},"uuid":"%s","name":"Bad id"},{"id":"../../etc","uuid":"%s","name":"Bad id"},{"id":1.5,"uuid":"%s","name":"Half"}],
			 "chat":{"with":"%s","messages":[{"id":1,"from":"%s","to":"%s","body":"not theirs"}]}}
			""".formatted(STEVE, ALEX, STEVE, ALEX, ALEX, ALEX, ALEX, STEVE, STEVE)), ALEX);
		assertEquals("error", f.phase(), "an unknown phase is not ready");
		assertEquals(1, f.friends().size());
		assertEquals("Alex", f.friends().get(0).name(), "control characters and direction marks dropped");
		assertEquals("offline", f.friends().get(0).status());
		assertEquals(0, f.friends().get(0).unread());
		assertEquals(0, f.incoming().size(), "ids must be whole numbers or plain short strings");
		assertTrue(f.chat().messages().isEmpty(), "a message that isn't with this friend never shows in their chat");
		assertNull(SocialLink.readFriends(json("{\"phase\":\"ready\",\"chat\":{\"with\":\"" + STEVE + "\"}}"), ALEX).chat(),
			"a chat for another friend than the one open is ignored");
	}

	@Test
	void formattingCodesNeverReachTheScreen() {
		assertEquals("gg red ", SocialLink.clean("gg §cred §k", 100, true), "a friend's text can't colour or scramble the game's");
		assertEquals("end", SocialLink.clean("end§", 100, true), "a lone code at the end goes too");
		Friends f = SocialLink.readFriends(json("""
			{"phase":"ready","friends":[{"uuid":"%s","name":"§kAlex","status":"online"}],
			 "chat":{"with":"%s","messages":[{"id":1,"from":"%s","to":"%s","body":"§l§ohi","at":0}]}}
			""".formatted(ALEX, ALEX, ALEX, STEVE)), ALEX);
		assertEquals("Alex", f.friends().get(0).name());
		assertEquals("hi", f.chat().messages().get(0).body());
	}

	@Test
	void listsAreCapped() {
		StringBuilder friends = new StringBuilder("{\"phase\":\"ready\",\"friends\":[");
		for (int i = 0; i < 400; i++) {
			friends.append(i > 0 ? "," : "").append("{\"uuid\":\"").append("%032x".formatted(i + 1)).append("\",\"name\":\"P").append(i).append("\"}");
		}
		friends.append("]}");
		assertEquals(SocialLink.MAX_FRIENDS, SocialLink.readFriends(json(friends.toString()), null).friends().size());
		String body = "x".repeat(5000);
		var message = SocialLink.readMessage(json("{\"id\":1,\"from\":\"" + STEVE + "\",\"to\":\"" + ALEX + "\",\"body\":\"" + body + "\"}"));
		assertEquals(SocialLink.MAX_BODY, message.body().length());
	}

	@Test
	void uuidsAndCodesAreTakenOnlyInTheirForms() {
		assertEquals(STEVE, SocialLink.canonical("8667BA71-B85A-4004-AF54-457A9734EED7"));
		assertNull(SocialLink.canonical("8667ba71b85a4004af54457a9734eed"));
		assertNull(SocialLink.canonical("../8667ba71b85a4004af54457a9734eed7"));
		assertEquals("not_on_flint", SocialLink.code("not_on_flint"));
		assertEquals("evilcode", SocialLink.code("evil<code>"));
		assertEquals("invalid", SocialLink.code("<>"));
		assertNull(SocialLink.requestId(new JsonPrimitive("a b")));
		assertEquals(new JsonPrimitive(7L), SocialLink.requestId(new JsonPrimitive(7)));
	}

	@Test
	void cosmeticsAreReadWithTheirSlotsAndPictures() {
		Cosmetics c = SocialLink.readCosmetics(json("""
			{"storeUrl":"https://store.flintclient.com","name":"Steve","items":[
			 {"id":"%s","slug":"ember","kind":"cape","name":"Ember Cape","gift":true,"giftFrom":"Alex","test":false,"art":3},
			 {"id":"not-a-uuid","kind":"cape","name":"Bad"},{"id":"00000000-0000-4000-8000-000000000002","kind":"boots","name":"Bad slot"},
			 {"id":"00000000-0000-4000-8000-000000000003","kind":"head","name":"Halo","art":null}],
			 "equipped":{"cape":"%s","boots":"%s","head":"x"},"stale":false,"problem":null}
			""".formatted(CAPE, CAPE, CAPE)));
		assertEquals(2, c.items().size());
		assertEquals(3, c.items().get(0).art());
		assertEquals(-1, c.items().get(1).art(), "no picture");
		assertEquals("Alex", c.items().get(0).giftFrom());
		assertEquals(CAPE, c.worn("cape"));
		assertNull(c.worn("head"));
		assertEquals(1, c.equipped().size(), "only known slots");
		assertEquals("https://store.flintclient.com", SocialLink.readCosmetics(json("{\"storeUrl\":\"javascript:alert(1)\"}")).storeUrl(),
			"an odd Store address falls back to the real Store");
	}

	@Test
	void aPictureMustBeAPngOfReasonableSize() throws Exception {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};
		JsonObject reply = new JsonObject();
		reply.addProperty("png", Base64.getEncoder().encodeToString(png));
		assertArrayEquals(png, SocialLink.readArt(reply));
		reply.addProperty("png", Base64.getEncoder().encodeToString("GIF89a....".getBytes()));
		assertThrows(LinkException.class, () -> SocialLink.readArt(reply));
		reply.addProperty("png", "%%%");
		assertThrows(LinkException.class, () -> SocialLink.readArt(reply));
		reply.addProperty("png", "A".repeat(SocialLink.MAX_ART_BYTES * 2));
		assertThrows(LinkException.class, () -> SocialLink.readArt(reply));
	}
}

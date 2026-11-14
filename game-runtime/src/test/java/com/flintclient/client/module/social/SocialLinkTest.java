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
	void friendsTypingNowAreRead() {
		Friends f = SocialLink.readFriends(json("{\"phase\":\"ready\",\"typing\":[\"" + ALEX + "\",\"ec561538-f3fd-461d-aff5-086b22154bce\",\"not a uuid\",7]}"), null);
		org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(ALEX), f.typing(), "canonical, once, nothing else");
		org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(), SocialLink.readFriends(json("{\"phase\":\"ready\"}"), null).typing(),
			"an app before typing sends none");
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
	void badgesAreReadAsPlainNamesAndNoListIsNull() {
		SocialLink.Badges b = SocialLink.readBadges(json("{\"badges\":[\"default\",\"staff\",\"<x>\",7],\"badge\":\"staff\"}"));
		assertEquals(java.util.List.of("default", "staff"), b.badges());
		assertEquals("staff", b.badge());
		SocialLink.Badges none = SocialLink.readBadges(json("{\"badges\":null,\"badge\":null}"));
		assertNull(none.badges(), "the Store has no badges yet: the game works its marks out");
		assertNull(SocialLink.readBadges(json("{}")).badges());
		assertNull(SocialLink.readBadges(json("{\"badges\":[\"default\"],\"badge\":\"Evil!\"}")).badge());
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
		assertEquals(3, c.items().size(), "a kind this game doesn't know yet (a newer Store's) is kept, to show under More");
		assertEquals(3, c.items().get(0).art());
		assertEquals("boots", c.items().get(1).kind());
		assertNull(c.items().get(1).slot(), "but never worn");
		assertEquals("headwear", c.items().get(2).kind(), "the Store's older name reads as today's");
		assertEquals("head", c.items().get(2).slot());
		assertEquals(-1, c.items().get(2).art(), "no picture");
		assertTrue(c.items().get(0).owned());
		assertNull(c.emotes(), "a Flint app without the emote wheel");
		assertEquals("Alex", c.items().get(0).giftFrom());
		assertEquals("ember", c.items().get(0).slug(), "the Store's slug: the item's page (/cosmetics/ember/)");
		assertNull(c.items().get(1).slug(), "no slug: the Store link opens the catalog");
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

	@Test
	void theShopTheEmoteWheelAndNewKindsAreRead() {
		String emote = "00000000-0000-4000-8000-0000000000e1";
		String wings = "00000000-0000-4000-8000-0000000000b1";
		Cosmetics c = SocialLink.readCosmetics(json("""
			{"items":[{"id":"%s","kind":"emote","name":"Wave"},{"id":"%s","kind":"wings","name":"Ember Wings","priceCoins":400,
			  "grantedAt":"2026-11-10T12:00:00Z","model":{"attach":{"anchor":"anchor_body"}}}],
			 "shop":[{"id":"00000000-0000-4000-8000-0000000000c1","kind":"pet","name":"Fox","priceCoins":900},{"id":"%s","kind":"wings","name":"twice"}],
			 "equipped":{"back":"%s","pet":"x"},"emotes":["%s",null,"00000000-0000-4000-8000-0000000000c1"]}
			""".formatted(emote, wings, wings, wings, emote)));
		assertEquals(wings, c.worn("back"));
		assertEquals("back", c.items().get(1).slot());
		assertEquals(400, c.items().get(1).price());
		assertEquals(java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli(), c.items().get(1).addedAt());
		assertEquals("anchor_body", c.items().get(1).model().getAsJsonObject("attach").get("anchor").getAsString());
		assertEquals(2, c.shop().size());
		assertFalse(c.shop().get(0).owned());
		assertEquals(900, c.shop().get(0).price());
		assertEquals(8, c.emotes().size(), "always the wheel's 8 places");
		assertEquals(emote, c.emotes().get(0));
		assertEquals("", c.emotes().get(1));
		assertEquals("", c.emotes().get(2), "only an owned emote stays on the wheel");
	}

	@Test
	void skinsAreReadCheckedAndCapped() {
		String id = "a".repeat(64);
		java.util.List<SocialLink.Skin> skins = SocialLink.readSkins(json("""
			{"skins":[{"id":"%s","name":"Notch","source":"username","origin":"Notch","variant":"slim","legacy":false,"addedAt":5},
			 {"id":"../x","name":"bad"},{"id":"%s","name":"Odd","source":"ftp","variant":"wide"}]}
			""".formatted(id, "b".repeat(64))));
		assertEquals(2, skins.size());
		assertEquals("slim", skins.get(0).variant());
		assertEquals("url", skins.get(1).source(), "an unknown source reads as an address");
		assertEquals("classic", skins.get(1).variant());
	}
}

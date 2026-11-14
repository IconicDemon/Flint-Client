package com.flintclient.client.module.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.flintclient.client.module.social.MenuLink.Community;
import com.flintclient.client.module.social.MenuLink.Partner;
import com.flintclient.client.module.social.MenuLink.Partners;
import com.flintclient.client.module.social.MenuLink.Showcase;
import com.flintclient.client.render.Pixels;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The main menu's answers from the Flint app (Discover, showcase, community) are read carefully: anything malformed is left out. */
class MenuLinkTest {
	private static final String ID = "7a1e0001-5c2d-4b8e-9f10-000000000001";
	private static final String SHA = "4d2f2a08373b35702a61eb1a0fcd3e4d76348127566434e5d105d9e3ba4b4d97";
	private static final String CAPE = "b2d1c0de-4c0d-4e5a-9b1e-00000000d15c";

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void partnersAreReadAsTheLauncherSendsThem() {
		Partners p = MenuLink.readPartners(json("""
			{"ok":true,"source":"network","problem":null,"servers":1,"partners":[
			 {"id":"%s","name":"Emberfall §cNetwork","kind":"minecraft_server","label":"Partner","summary":"Survival\\u0007 and Bedwars","featured":true,
			  "address":"play.emberfall.example","edition":"java","joinable":true,"versions":"1.8.9 – 26.3","recommended":"1.21.11",
			  "stats":{"online":true,"players":1284,"max":5000},"members":null,"membersOnline":null,
			  "banner":{"sha":"%s","width":1600,"height":400},"logo":{"sha":"%s"},
			  "links":{"website":"https://emberfall.example/","discord":"javascript:alert(1)","x":"http://x.com/e"},
			  "primary":{"kind":"join_server","label":"Play","url":null}},
			 {"id":"not-a-uuid","name":"Bad","kind":"minecraft_server","label":"Partner","summary":"","address":"a.example"},
			 {"id":"7a1e0001-5c2d-4b8e-9f10-000000000002","name":"No address","kind":"minecraft_server","label":"Partner","summary":""},
			 {"id":"7a1e0001-5c2d-4b8e-9f10-000000000003","name":"Guild","kind":"discord","label":"Sponsored","summary":"Builders",
			  "members":18240,"links":{"discord":"https://discord.gg/guild"},"primary":{"kind":"open_url","url":"https://discord.gg/guild"}},
			 {"id":"%s","name":"Twice","kind":"hosting","label":"Partner","summary":""}]}
			""".formatted(ID, SHA, SHA, ID)));
		assertEquals(2, p.partners().size(), "malformed, server without an address and a repeated id are left out");
		assertEquals(1, p.servers());
		Partner ember = p.partners().get(0);
		assertEquals("Emberfall Network", ember.name(), "formatting codes are removed");
		assertEquals("Survival and Bedwars", ember.summary(), "control characters are removed");
		assertEquals("play.emberfall.example", ember.address());
		assertTrue(ember.joinable());
		assertEquals(1284, ember.stats().players());
		assertEquals(SHA, ember.banner().sha());
		assertEquals(List.of("website"), List.copyOf(ember.links().keySet()), "only https links are kept");
		Partner guild = p.partners().get(1);
		assertFalse(guild.server());
		assertEquals(18240, guild.members());
		assertEquals("https://discord.gg/guild", guild.primaryUrl());
		assertTrue(MenuLink.readPartners(json("{\"ok\":true,\"problem\":\"offline\"}")).partners().isEmpty());
		assertEquals("offline", MenuLink.readPartners(json("{\"ok\":true,\"problem\":\"offline\"}")).problem());
	}

	@Test
	void picturesMustBeWhatTheySay() throws LinkException {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0};
		byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
		String b64 = Base64.getEncoder().encodeToString(png);
		assertTrue(MenuLink.readArt(json("{\"type\":\"png\",\"data\":\"" + b64 + "\"}")).png());
		assertFalse(MenuLink.readArt(json("{\"type\":\"jpeg\",\"data\":\"" + Base64.getEncoder().encodeToString(jpeg) + "\"}")).png());
		assertThrows(LinkException.class, () -> MenuLink.readArt(json("{\"type\":\"jpeg\",\"data\":\"" + b64 + "\"}")), "a PNG named a JPEG");
		assertThrows(LinkException.class, () -> MenuLink.readArt(json("{\"type\":\"webp\",\"data\":\"" + b64 + "\"}")));
		assertThrows(LinkException.class, () -> MenuLink.readArt(json("{\"type\":\"png\",\"data\":\"%%%\"}")));
	}

	@Test
	void aJoinNamesAServerAddressOnly() throws LinkException {
		assertEquals("play.emberfall.example:25570", MenuLink.readJoin(json("{\"name\":\"Emberfall\",\"address\":\"play.emberfall.example:25570\"}")).address());
		assertEquals("[2001:db8::1]:25565", MenuLink.readJoin(json("{\"name\":\"V6\",\"address\":\"[2001:db8::1]:25565\"}")).address());
		assertThrows(LinkException.class, () -> MenuLink.readJoin(json("{\"name\":\"Evil\",\"address\":\"a.example/../x\"}")));
		assertThrows(LinkException.class, () -> MenuLink.readJoin(json("{\"name\":\"Evil\",\"address\":\"a.example\\nb\"}")));
	}

	@Test
	void theCommunityViewSaysWhatTheStoreSaid() {
		Community c = MenuLink.readCommunity(json("""
			{"ok":true,"state":"ok","enabled":true,"discordEnabled":true,"canLink":true,"invite":"https://discord.gg/flintclient",
			 "discord":{"username":"flintqa","displayName":"Flint QA","member":true,"booster":false,"guild":"verified"},
			 "standing":{"linked":true,"member":true,"booster":false,"pending":false,"state":"verified"},
			 "rewards":[{"id":"%s","kind":"cape","name":"Discord Member Cape","requirement":"discord_member","label":"Member",
			   "permanence":"conditional","status":"eligible","animated":false,"art":3},
			  {"id":"%s","kind":"boots","name":"Bad slot","requirement":"discord_member","status":"owned"},
			  {"id":"%s","kind":"cape","name":"Launch cape","requirement":"first_launch","status":"owned","art":1}]}
			""".formatted(CAPE, CAPE, "00000000-0000-4000-8000-000000000001")));
		assertTrue(c.linked());
		assertTrue(c.member());
		assertEquals("Flint QA", c.discord().shownName());
		assertEquals(2, c.rewards().size(), "an unknown slot is left out");
		assertEquals(1, c.discordRewards().size());
		assertTrue(c.discordRewards().get(0).unlocked());
		assertEquals(3, c.discordRewards().get(0).art());
		Community signedOut = MenuLink.readCommunity(json("{\"state\":\"signedOut\",\"discordEnabled\":true,\"invite\":\"https://evil.example/x\",\"rewards\":[]}"));
		assertFalse(signedOut.linked());
		assertEquals(MenuLink.DEFAULT_INVITE, signedOut.invite(), "an invite that isn't Discord's is replaced by Flint's");
		assertEquals("off", MenuLink.readCommunity(json("{\"state\":\"nonsense\"}")).state());
	}

	@Test
	void onlyDiscordsOwnSignInPageIsOpened() throws LinkException {
		assertEquals("https://discord.com/oauth2/authorize?client_id=1", MenuLink.readLinkStart(json(
			"{\"state\":\"started\",\"url\":\"https://discord.com/oauth2/authorize?client_id=1\",\"attemptId\":\"x\"}")).url());
		assertTrue(MenuLink.readLinkStart(json("{\"state\":\"off\"}")).off());
		assertThrows(LinkException.class, () -> MenuLink.readLinkStart(json("{\"state\":\"started\",\"url\":\"https://discord.com.evil.example/oauth2/authorize?x\"}")));
	}

	@Test
	void slidesAreCheckedAndTheTurnIsKeptReasonable() {
		Showcase s = MenuLink.readShowcase(json("""
			{"ok":true,"rotateMs":100,"slides":[
			 {"id":"season-4","title":"Season 4","alt":"Season 4","link":"https://flintclient.com/s","width":960,"height":540,"weight":10,"endsAt":null},
			 {"id":"no-link","title":"","alt":"Alt words","link":null,"width":1920,"height":1080,"weight":1,"endsAt":1800000000000},
			 {"id":"../evil","title":"x","width":960,"height":540},
			 {"id":"tiny","title":"x","width":8,"height":8},
			 {"id":"season-4","title":"twice","width":960,"height":540}]}
			"""));
		assertEquals(2, s.slides().size());
		assertEquals(3000, s.rotateMillis(), "a turn under 3 s is made 3 s");
		assertNull(s.slides().get(1).link());
		assertEquals("Alt words", s.slides().get(1).alt());
		assertEquals(1800000000000L, s.slides().get(1).endsAt());
		assertEquals(6000, MenuLink.readShowcase(json("{\"slides\":[]}")).rotateMillis());
	}

	@Test
	void anOlderAppsAnswerHidesTheFeature() {
		assertTrue(MenuLink.missing(new LinkException("bad request", false)));
		assertFalse(MenuLink.missing(new LinkException("Flint isn't reachable.", true)));
		assertFalse(MenuLink.missing(new LinkException("not found", false)));
	}

	@Test
	void picturesAreScaledDownByAveraging() {
		int[] argb = new int[4 * 2];
		java.util.Arrays.fill(argb, 0xFFFFFFFF);
		argb[0] = 0xFF000000;
		argb[1] = 0xFF000000;
		argb[4] = 0xFF000000;
		argb[5] = 0xFF000000;
		Pixels small = MenuArt.fitPixels(new Pixels(4, 2, argb), 2, 2);
		assertEquals(2, small.width());
		assertEquals(1, small.height());
		assertEquals(0xFF000000, small.argb()[0]);
		assertEquals(0xFFFFFFFF, small.argb()[1]);
		Pixels same = new Pixels(2, 2, new int[4]);
		assertTrue(MenuArt.fitPixels(same, 10, 10) == same, "never enlarged");
	}
}

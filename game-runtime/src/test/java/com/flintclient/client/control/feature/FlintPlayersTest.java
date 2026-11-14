package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlintPlayersTest {
	@AfterEach
	void reset() {
		FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
		FlintPlayers.forgetChoice();
		FlintPlayers.clear();
	}

	@Test
	void youOwnTheFlintMarkAndTheOneFlintGivesAndWearTheOneYouPick() {
		assertEquals(java.util.List.of(FlintPlayers.Tag.DEFAULT), FlintPlayers.owned(), "everyone has the Flint mark");
		FlintPlayers.wear(FlintPlayers.Tag.OWNER);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "a mark that isn't yours can't be worn");
		FlintPlayers.setOwn(FlintPlayers.Tag.STAFF);
		assertEquals(java.util.List.of(FlintPlayers.Tag.STAFF, FlintPlayers.Tag.DEFAULT), FlintPlayers.owned());
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.worn(), "the given mark is worn until you pick another");
		FlintPlayers.wear(FlintPlayers.Tag.DEFAULT);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn());
		FlintPlayers.setOwn(FlintPlayers.Tag.DEVELOPER);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "the choice of the Flint mark stays with a new given mark");
		FlintPlayers.wear(FlintPlayers.Tag.DEVELOPER);
		FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "a mark Flint took away is no longer worn");
	}

	@Test
	void youWearTheDefaultMarkUntilFlintGivesYouAnother() {
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.own());
		FlintPlayers.setOwnNamed("content_creator");
		assertEquals(FlintPlayers.Tag.CONTENT_CREATOR, FlintPlayers.own());
		FlintPlayers.setOwnNamed("nobody");
		assertEquals(FlintPlayers.Tag.CONTENT_CREATOR, FlintPlayers.own(), "an unknown name changes nothing");
	}

	@Test
	void otherPlayersHaveAMarkOnlyOnceKnown() {
		UUID someone = UUID.randomUUID();
		assertNull(FlintPlayers.tag(someone));
		FlintPlayers.set(someone, FlintPlayers.Tag.STAFF);
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.tag(someone));
		FlintPlayers.clear();
		assertNull(FlintPlayers.tag(someone));
	}

	@Test
	void theAppsListIsWhatYouOwnAndTheStoresChoiceIsWorn() {
		FlintPlayers.setOwn(FlintPlayers.Tag.STAFF);
		FlintPlayers.fromLauncher(java.util.List.of("default", "staff", "vip", "a_newer_mark"), "vip");
		assertEquals(java.util.List.of(FlintPlayers.Tag.DEFAULT, FlintPlayers.Tag.STAFF, FlintPlayers.Tag.VIP), FlintPlayers.owned(),
			"the app's marks in its order; a name this game doesn't know is left out");
		assertEquals(FlintPlayers.Tag.VIP, FlintPlayers.worn(), "the one the Store says others see");
		FlintPlayers.setOwn(FlintPlayers.Tag.DEFAULT);
		assertEquals(FlintPlayers.Tag.VIP, FlintPlayers.worn(), "presence doesn't change your own mark while the app's list is there");
		FlintPlayers.wear(FlintPlayers.Tag.OWNER);
		assertEquals(FlintPlayers.Tag.VIP, FlintPlayers.worn(), "a mark that isn't yours can't be worn");
		FlintPlayers.wear(FlintPlayers.Tag.DEFAULT);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "a pick shows at once, before the Store answers");
		FlintPlayers.fromLauncher(java.util.List.of("default", "staff", "vip"), "default");
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "the Store's answer");
		assertNull(FlintPlayers.pick(), "the answer replaces the pick on its way");
	}

	@Test
	void aRefusedPickRollsBack() {
		FlintPlayers.fromLauncher(java.util.List.of("default", "staff"), "staff");
		FlintPlayers.Tag before = FlintPlayers.pick();
		FlintPlayers.wear(FlintPlayers.Tag.DEFAULT);
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn());
		FlintPlayers.restorePick(before);
		assertEquals(FlintPlayers.Tag.STAFF, FlintPlayers.worn(), "back to what the Store says");
	}

	@Test
	void theFlintMarkIsAlwaysThereAndNoListMeansTodaysMarks() {
		FlintPlayers.fromLauncher(java.util.List.of("staff"), "nobody");
		assertEquals(java.util.List.of(FlintPlayers.Tag.DEFAULT, FlintPlayers.Tag.STAFF), FlintPlayers.owned());
		assertEquals(FlintPlayers.Tag.DEFAULT, FlintPlayers.worn(), "an unknown choice shows the Flint mark");
		assertEquals(true, FlintPlayers.fromLauncher());
		FlintPlayers.fromLauncher(null, null);
		assertEquals(false, FlintPlayers.fromLauncher());
		FlintPlayers.setOwn(FlintPlayers.Tag.DEVELOPER);
		assertEquals(java.util.List.of(FlintPlayers.Tag.DEVELOPER, FlintPlayers.Tag.DEFAULT), FlintPlayers.owned(), "as before: worked out here");
		assertEquals(FlintPlayers.Tag.DEVELOPER, FlintPlayers.worn());
		FlintPlayers.fromLauncher(java.util.List.of("default", "owner"), "owner");
		FlintPlayers.forgetChoice();
		assertEquals(false, FlintPlayers.fromLauncher(), "another account: the app's list goes");
	}

	@Test
	void eachTagsGlyphIsItsPictureInTheBadgeFont() throws Exception {
		try (InputStream in = FlintPlayersTest.class.getResourceAsStream("/assets/flintclient/font/badge.json")) {
			assertNotNull(in, "the badge font ships");
			JsonObject font = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray providers = font.getAsJsonArray("providers");
			assertEquals(FlintPlayers.Tag.values().length, providers.size());
			for (FlintPlayers.Tag tag : FlintPlayers.Tag.values()) {
				JsonObject provider = providers.get(tag.ordinal()).getAsJsonObject();
				assertEquals(String.valueOf(tag.glyph()), provider.getAsJsonArray("chars").get(0).getAsString());
				String file = "flintclient:font/tag_" + tag.name().toLowerCase(Locale.ROOT) + ".png";
				assertEquals(file, provider.get("file").getAsString());
				assertNotNull(FlintPlayersTest.class.getResource("/assets/flintclient/textures/font/tag_" + tag.name().toLowerCase(Locale.ROOT) + ".png"),
					tag + "'s picture ships");
			}
		}
	}
}

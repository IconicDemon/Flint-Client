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
		FlintPlayers.clear();
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

package com.flintclient.legacy.mods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.SoundActivity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 1.8.9's captions for Nearby Sound Activity: only sounds 1.8.9 has (its own sounds.json's 246 events, in
 * {@code legacy189/sounds-1.8.9.txt}), every one of them but music, records and the menu button captioned.
 */
class SoundCaptions189Test {
	private static List<String> sounds() throws IOException {
		List<String> names = new ArrayList<>();
		try (InputStream in = SoundCaptions189Test.class.getResourceAsStream("/legacy189/sounds-1.8.9.txt");
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			for (String line; (line = r.readLine()) != null;) {
				if (!line.isBlank()) {
					names.add(line.strip());
				}
			}
		}
		return names;
	}

	@Test
	void everySoundOf189WithAMeaningIsCaptionedAndNothingElse() throws IOException {
		List<String> all = sounds();
		assertEquals(246, all.size());
		int captioned = 0;
		for (String name : all) {
			SoundCaptions189.Caption c = SoundCaptions189.of(name, 1);
			boolean silent = name.startsWith("music.") || name.startsWith("records.") || name.equals("gui.button.press")
				|| name.equals("mob.bat.loop");
			if (silent) {
				assertNull(c, name + " has no caption in any Minecraft");
			} else {
				assertNotNull(c, name + " is captioned");
				assertFalse(c.text().isBlank(), name);
				captioned++;
			}
		}
		assertEquals(captioned, SoundCaptions189.size(), "every caption is for a sound 1.8.9 has");
		assertNull(SoundCaptions189.of("block.chest.open", 1), "a modern name means nothing on 1.8.9");
	}

	@Test
	void captionsUseMinecraftsOwnWording() {
		assertEquals("Chest opens", SoundCaptions189.of("random.chestopen", 1).text());
		assertEquals("Chest closes", SoundCaptions189.of("random.chestclosed", 1).text());
		assertEquals("Footsteps", SoundCaptions189.of("step.stone", 1).text());
		assertEquals("Bottle smashes", SoundCaptions189.of("game.potion.smash", 1).text());
		assertEquals("TNT fizzes", SoundCaptions189.of("game.tnt.primed", 1).text());
		assertEquals("Explosion", SoundCaptions189.of("random.explode", 4).text());
		assertEquals("Zombie groans", SoundCaptions189.of("mob.zombie.say", 1).text());
		assertEquals(SoundActivity.COMBAT, SoundCaptions189.of("random.explode", 4).group());
		assertEquals(SoundActivity.ENTITIES, SoundCaptions189.of("mob.zombie.say", 1).group());
		assertEquals(SoundActivity.BLOCKS, SoundCaptions189.of("random.door_open", 1).group());
		assertEquals(SoundActivity.PLAYERS, SoundCaptions189.of("random.eat", 1).group());
		assertEquals(SoundActivity.AMBIENT, SoundCaptions189.of("ambient.weather.rain", 1).group());
	}

	@Test
	void anArrowShotAndAThrownPearlShareOneSoundButNotOneCaption() {
		// 1.8.9 plays random.bow at full volume for a bow, at half for pearls, potions, snowballs, eggs and a fishing line.
		assertSame(SoundCaptions189.ARROW_FIRED, SoundCaptions189.of("random.bow", 1));
		assertSame(SoundCaptions189.THROWN, SoundCaptions189.of("random.bow", 0.5F));
		assertTrue(SoundCaptions189.of("random.bow", 0.5F).group() == SoundActivity.COMBAT);
	}
}

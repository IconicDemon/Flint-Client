package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SoundMixerTest {
	@Test
	void categoriesComeFromThePath() {
		assertEquals(1, SoundMixer.category("entity.zombie.ambient"));
		assertEquals(5, SoundMixer.category("music_disc.cat"));
		assertEquals(3, SoundMixer.category("music.game"));
		assertEquals(SoundMixer.OTHER, SoundMixer.category("intentionally_empty"));
	}

	@Test
	void categoryAndOwnVolumeMultiply() {
		int[] categories = new int[SoundMixer.CATEGORIES.length + 1];
		Arrays.fill(categories, 100);
		categories[1] = 50;
		assertEquals(0.5F, SoundMixer.factor("minecraft:entity.zombie.ambient", categories, Map.of()), 1e-6);
		assertEquals(0.25F, SoundMixer.factor("minecraft:entity.zombie.ambient", categories, Map.of("minecraft:entity.zombie.ambient", 50)),
			1e-6);
		assertEquals(0F, SoundMixer.factor("minecraft:block.stone.break", categories, Map.of("minecraft:block.stone.break", 0)), 1e-6);
		assertEquals(1F, SoundMixer.factor("othermod:weird", categories, Map.of()), 1e-6);
	}
}

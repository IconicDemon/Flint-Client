package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockCamouflageTest {
	private static String seen(String path) {
		return BlockCamouflage.disguise(path, false, false);
	}

	@Test
	void trapsAreNamedAsTheBlockTheyPassFor() {
		assertEquals("chest", seen("trapped_chest"));
		assertEquals("copper_chest", seen("copper_trapped_chest"), "a mod's or a newer chest's trapped kind");
		assertEquals("stone", seen("infested_stone"));
		assertEquals("cobblestone", seen("infested_cobblestone"));
		assertEquals("stone_bricks", seen("infested_stone_bricks"));
		assertEquals("chiseled_stone_bricks", seen("infested_chiseled_stone_bricks"));
		assertEquals("deepslate", seen("infested_deepslate"));
		assertEquals("snow_block", seen("powder_snow"));
	}

	@Test
	void everyOtherBlockIsItself() {
		for (String path : new String[] {"chest", "ender_chest", "barrel", "stone", "tripwire", "tnt", "snow", "snow_block", "trapped", "infested",
			"_trapped_chest", "oak_pressure_plate", "sculk_sensor"}) {
			assertEquals(path, seen(path), path);
		}
	}

	@Test
	void creativeAndSpectatorSeeTheRealBlock() {
		assertEquals("trapped_chest", BlockCamouflage.disguise("trapped_chest", true, false));
		assertEquals("infested_stone", BlockCamouflage.disguise("infested_stone", true, false));
		assertEquals("powder_snow", BlockCamouflage.disguise("powder_snow", true, false));
	}

	@Test
	void powderSnowIsKnownForEightSecondsAfterYouWereInIt() {
		assertEquals("powder_snow", BlockCamouflage.disguise("powder_snow", false, true));
		assertEquals("chest", BlockCamouflage.disguise("trapped_chest", false, true), "only powder snow");
		assertFalse(BlockCamouflage.knowsPowderSnow(-1), "never in it");
		assertTrue(BlockCamouflage.knowsPowderSnow(0));
		assertTrue(BlockCamouflage.knowsPowderSnow(BlockCamouflage.POWDER_SNOW_KNOWN_MILLIS));
		assertFalse(BlockCamouflage.knowsPowderSnow(BlockCamouflage.POWDER_SNOW_KNOWN_MILLIS + 1));
	}
}

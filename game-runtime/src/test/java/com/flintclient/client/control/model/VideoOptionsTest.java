package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Minecraft's video options as the Graphics tab shows them: names for special values, Minecraft's defaults, unique ids. */
class VideoOptionsTest {
	@Test
	void specialValuesReadAsMinecraftsOwnWords() {
		assertEquals("Unlimited", VideoOptions.MAX_FPS.format(260));
		assertEquals("120 FPS", VideoOptions.MAX_FPS.format(120));
		assertEquals("Auto", VideoOptions.GUI_SCALE.format(0));
		assertEquals("3", VideoOptions.GUI_SCALE.format(3));
		assertEquals("Off", VideoOptions.BIOME_BLEND.format(0));
		assertEquals("5×5", VideoOptions.BIOME_BLEND.format(2));
		assertEquals("12 chunks", VideoOptions.RENDER_DISTANCE.format(12));
		assertEquals("Normal", VideoOptions.FOV.format(70));
		assertEquals("Off", VideoOptions.MIPMAPS.format(0));
		assertFalse(VideoOptions.RENDER_DISTANCE.hasNames());
	}

	@Test
	void defaultsAreMinecrafts() {
		assertEquals(12, VideoOptions.RENDER_DISTANCE.defaultValue());
		assertEquals(12, VideoOptions.SIMULATION_DISTANCE.defaultValue());
		assertEquals(120, VideoOptions.MAX_FPS.defaultValue());
		assertEquals(70, VideoOptions.FOV.defaultValue());
		assertEquals(50, VideoOptions.BRIGHTNESS.defaultValue());
		assertEquals(1, VideoOptions.GRAPHICS.defaultValue());
		assertEquals(4, VideoOptions.MIPMAPS.defaultValue());
		assertEquals(2, VideoOptions.BIOME_BLEND.defaultValue());
	}

	@Test
	void idsAreUniqueAndFound() {
		Set<String> ids = new HashSet<>();
		for (SettingDescriptor<?> option : VideoOptions.ALL) {
			assertTrue(ids.add(option.id()), option.id());
			assertSame(option, VideoOptions.byId(option.id()));
		}
		assertNull(VideoOptions.byId("nope"));
		assertTrue(VideoOptions.appliesOnRelease(VideoOptions.RENDER_DISTANCE));
		assertFalse(VideoOptions.appliesOnRelease(VideoOptions.FOV));
	}
}

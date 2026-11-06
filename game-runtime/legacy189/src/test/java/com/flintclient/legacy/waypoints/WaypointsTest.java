package com.flintclient.legacy.waypoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointsTest {
	@TempDir
	Path dir;

	@Test
	void distanceReadsAsWWaypoints() {
		assertEquals("[0m]", LabelMath.distanceText(0));
		assertEquals("[999m]", LabelMath.distanceText(999));
		assertEquals("[1.0K]", LabelMath.distanceText(1000));
		assertEquals("[1.2K]", LabelMath.distanceText(1234));
		assertEquals("[10K]", LabelMath.distanceText(9999));
		assertEquals("[45K]", LabelMath.distanceText(45_400));
		assertEquals("[3.4M]", LabelMath.distanceText(3_400_000));
	}

	@Test
	void labelsShrinkGentlyWithDistance() {
		assertEquals(1.03, LabelMath.scale(0.5), 0.01);
		assertEquals(0.92, LabelMath.scale(10), 0.01);
		assertEquals(0.585, LabelMath.scale(1000), 0.01);
		assertEquals(0.35, LabelMath.scale(100_000), 0.01);
		assertTrue(LabelMath.scale(100) > LabelMath.scale(200));
	}

	@Test
	void fadesNearAndWhenLookingAway() {
		assertEquals(0, LabelMath.nearFade(1, 2, 1), 1e-6);
		assertEquals(0.5, LabelMath.nearFade(1.5, 2, 1), 1e-6);
		assertEquals(1, LabelMath.nearFade(3, 2, 1), 1e-6);
		assertEquals(1, LabelMath.facingAlpha(4), 1e-6);
		assertEquals(0.5, LabelMath.facingAlpha(7), 1e-6);
		assertEquals(0, LabelMath.facingAlpha(10), 1e-6);
		assertEquals(1, LabelMath.aimAlpha(1), 1e-6);
		assertEquals(0, LabelMath.aimAlpha(8), 1e-6);
		assertEquals(90, LabelMath.angle(0, 0, 1, 1, 0, 0), 1e-6);
	}

	@Test
	void lettersAndTitles() {
		assertEquals("W", new Waypoint("", 1, 2, 3, 0, 0).letter());
		assertEquals("H", new Waypoint("home", 1, 2, 3, 0, 0).letter());
		assertEquals("1 2 3", new Waypoint("  ", 1, 2, 3, 0, 0).title());
		assertEquals("ab", new Waypoint("a§cb\n", 0, 0, 0, 0, 0).name());
	}

	/** A store over a shared store of its own (the game's is {@code Features.waypoints()}). */
	private WaypointStore store() {
		return new WaypointStore(dir.toFile(), new com.flintclient.client.control.feature.WaypointStore(dir.resolve("config/waypoints")));
	}

	@Test
	void keptPerWorldInTheSharedWaypoints() {
		com.flintclient.client.control.feature.WaypointStore shared = new com.flintclient.client.control.feature.WaypointStore(
			dir.resolve("config/waypoints"));
		WaypointStore store = new WaypointStore(dir.toFile(), shared);
		assertFalse(store.add(new Waypoint("x", 0, 0, 0, 0, 0)), "no world open");
		store.open("sp-My World");
		assertEquals("sp:My World", shared.worldKey(), "the key every line uses for this world");
		Waypoint home = new Waypoint("Home", 10, 64, -20, 0, store.nextColor());
		assertTrue(store.add(home));
		store.add(new Waypoint("Fortress", 5, 70, 5, -1, store.nextColor()));
		store.setVisible(home, false);
		store.recolor(home, 0x123456);
		assertSame(home, store.at(10, 64, -20, 0));
		assertNull(store.at(10, 64, -20, -1));
		assertEquals("minecraft:the_nether", shared.all().get(1).dimension(), "1.8.9's dimension as every line names it");
		assertFalse(shared.all().get(0).visible());
		store.open("mp-Play.Example.net");
		assertEquals("mp:play.example.net", shared.worldKey());
		assertEquals(0, store.array().length);
		store.open("sp-My World");
		assertEquals(2, store.array().length);
		Waypoint read = store.array()[0];
		assertEquals("Home", read.name());
		assertEquals(0x123456, read.rgb());
		assertFalse(read.visible());
		assertEquals(-1, store.array()[1].dimension());
		// A change made in the shared menu shows here.
		shared.set(0, shared.all().get(0).withName("Base"));
		assertEquals("Base", store.array()[0].name());
	}

	@Test
	void anOld189FileIsCarriedOverOnce() throws Exception {
		File old = new File(dir.toFile(), "flint/waypoints-1.8.9/sp-old_world.json");
		old.getParentFile().mkdirs();
		Files.write(old.toPath(), ("{\"schema\":1,\"waypoints\":[{\"name\":\"Mine\",\"x\":1,\"y\":12,\"z\":3,\"dimension\":0,\"color\":\"#21D85B\","
			+ "\"visible\":true},{\"name\":\"Hub\",\"x\":0,\"y\":80,\"z\":0,\"dimension\":1,\"color\":\"#FFFFFF\",\"visible\":false}]}")
			.getBytes(StandardCharsets.UTF_8));
		WaypointStore store = store();
		store.open("sp-Old World");
		assertEquals(2, store.array().length);
		assertEquals("Mine", store.array()[0].name());
		assertEquals(0x21D85B, store.array()[0].rgb());
		assertEquals(1, store.array()[1].dimension());
		assertFalse(store.array()[1].visible());
		assertFalse(old.isFile(), "the old file is moved aside");
		assertTrue(new File(old.getPath() + ".migrated").isFile());
		store.open(null);
		store.open("sp-Old World");
		assertEquals(2, store.array().length, "carried over once");
	}

	@Test
	void deathWaypointsKeepTheNewest() {
		WaypointStore store = store();
		store.open("sp-deaths");
		for (int i = 0; i < 5; i++) {
			store.addDeath(i, 64, 0, 0, 3);
		}
		int deaths = 0;
		int latest = 0;
		for (Waypoint w : store.array()) {
			assertTrue(w.death());
			assertEquals(WaypointStore.DEATH_COLOR, w.rgb());
			deaths++;
			if (WaypointStore.LATEST_DEATH.equals(w.name())) {
				latest++;
				assertEquals(4, w.x());
			}
		}
		assertEquals(3, deaths);
		assertEquals(1, latest);
	}

	@Test
	void colorsFollowThePaletteByHowManyThereAre() {
		WaypointStore store = store();
		store.open("sp-colours");
		for (int i = 0; i < WaypointStore.PALETTE.length + 1; i++) {
			int expected = WaypointStore.PALETTE[i % WaypointStore.PALETTE.length];
			assertEquals(expected, store.nextColor());
			assertTrue(store.add(new Waypoint("W" + i, i, 64, 0, 0, store.nextColor())));
		}
	}
}

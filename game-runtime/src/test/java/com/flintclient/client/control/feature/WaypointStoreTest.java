package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Waypoints are kept per world or server, survive a restart, and never mix between worlds. */
class WaypointStoreTest {
	@TempDir
	Path dir;

	@Test
	void eachWorldKeepsItsOwnListAcrossARestart() {
		WaypointStore store = new WaypointStore(dir);
		store.enter("sp:Survival");
		store.add(new Waypoint("Home", 10, 64, -20, "minecraft:overworld", 0xFF8139, true));
		store.add(new Waypoint("Portal", 1, 70, 2, "minecraft:the_nether", 0x00FF00, false));
		store.enter("mp:play.example.net");
		assertTrue(store.all().isEmpty(), "another world's list is empty");
		store.add(new Waypoint("Spawn", 0, 80, 0, "minecraft:overworld", 0xFFFFFF, true));

		WaypointStore again = new WaypointStore(dir);
		again.enter("sp:Survival");
		assertEquals(2, again.all().size());
		assertEquals("Home", again.all().get(0).name());
		assertEquals("minecraft:the_nether", again.all().get(1).dimension());
		assertEquals(false, again.all().get(1).visible());
		again.set(0, again.all().get(0).withName("Base").withPosition(5, 60, 5));
		again.remove(1);
		WaypointStore third = new WaypointStore(dir);
		third.enter("sp:Survival");
		assertEquals(1, third.all().size());
		assertEquals(new Waypoint("Base", 5, 60, 5, "minecraft:overworld", 0xFF8139, true), third.all().get(0));
		third.enter("mp:play.example.net");
		assertEquals("Spawn", third.all().get(0).name());
	}

	@Test
	void fileNamesAreReadableAndNeverCollide() {
		String a = WaypointStore.fileName("mp:Play.Example.net");
		String b = WaypointStore.fileName("mp:play.example.net");
		assertTrue(a.startsWith("mp_play.example.net-"), a);
		assertNotEquals(a, b, "the hash tells keys apart that clean to the same text");
		assertTrue(WaypointStore.fileName("sp:../../etc").matches("[a-z0-9._-]+\\.json"), "no path tricks");
	}

	@Test
	void badEntriesAreSkippedAndNamesCleaned() throws Exception {
		WaypointStore store = new WaypointStore(dir);
		Files.writeString(dir.resolve(WaypointStore.fileName("sp:x")), """
			{"schema": 1, "waypoints": [
			  {"name": "Good", "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld", "color": "#123456"},
			  {"name": "No coordinates"},
			  "not an object",
			  {"name": "\\u00A7cRed\\nline", "x": 99999999, "y": 9999, "z": 0, "color": "nope"}
			]}""");
		store.enter("sp:x");
		assertEquals(2, store.all().size());
		Waypoint odd = store.all().get(1);
		assertEquals(Waypoint.LIMIT, odd.x(), "clamped to the world border range");
		assertEquals(2048, odd.y());
		assertEquals(0xFF8139, odd.rgb(), "bad colour: the default");
		assertTrue(!odd.name().contains("\n"), odd.name());
		assertEquals("Waypoint 1", store.nextName("Waypoint"));
	}
}

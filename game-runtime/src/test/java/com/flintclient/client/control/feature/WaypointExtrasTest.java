package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class WaypointExtrasTest {
	@Test
	void extrasRoundTrip() {
		Waypoint w = new Waypoint("Base", -120, 64, 3000, "minecraft:the_nether", 0x123456, true).withGroup("Homes")
			.withShows(false, true, false, true);
		Waypoint back = WaypointStore.read(WaypointStore.write(w));
		assertEquals(w, back);
	}

	@Test
	void olderFilesGetTheUsualExtras() {
		Waypoint old = WaypointStore.read(JsonParser.parseString("{\"name\":\"Old\",\"x\":1,\"y\":2,\"z\":3}"));
		assertEquals("", old.group());
		assertTrue(old.showText() && old.showDistance() && old.beam());
		assertFalse(old.highlight());
	}

	@Test
	void searchMatchesNameAndGroup() {
		Waypoint w = new Waypoint("Iron farm", 0, 0, 0, "minecraft:overworld", 0, true).withGroup("Farms");
		assertTrue(w.matches("farm"));
		assertTrue(w.matches("FARMS iron"));
		assertFalse(w.matches("gold"));
		assertTrue(w.matches("  "));
		assertEquals("", new Waypoint("x", 0, 0, 0, "d", 0, true).withGroup("§c\n").group());
	}
}

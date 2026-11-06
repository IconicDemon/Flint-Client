package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WaypointShareTest {
	@Test
	void aSharedLineReadsBack() {
		Waypoint home = new Waypoint("Home (old)", 120, 64, -30, "minecraft:the_nether", 0xFF8139, true);
		String line = WaypointShare.line(home);
		assertEquals("[Flint waypoint] Home [old] at x 120, y 64, z -30 (Nether)", line);
		WaypointShare.Found found = WaypointShare.find("<Steve> " + line, false);
		assertNotNull(found);
		assertEquals("Home [old]", found.name());
		assertEquals(120, found.x());
		assertEquals(64, found.y());
		assertEquals(-30, found.z());
		assertEquals("minecraft:the_nether", found.dimension());
	}

	@Test
	void otherFormsAreUnderstood() {
		WaypointShare.Found xaero = WaypointShare.find("xaero-waypoint:Base:B:-512:70:1024:6:false:0:Internal-overworld-waypoints", false);
		assertNotNull(xaero);
		assertEquals("Base", xaero.name());
		assertEquals(-512, xaero.x());
		assertEquals(1024, xaero.z());
		assertEquals("minecraft:overworld", xaero.dimension());
		WaypointShare.Found letters = WaypointShare.find("meet at X: 100 Y: 65 Z: -200 now", false);
		assertNotNull(letters);
		assertNull(letters.name());
		assertEquals(65, letters.y());
		assertNull(WaypointShare.find("the score was 120, 64, -30", false), "bare numbers only when asked");
		WaypointShare.Found numbers = WaypointShare.find("the farm is at 120, 64, -30", true);
		assertNotNull(numbers);
		assertEquals(-30, numbers.z());
		assertNull(WaypointShare.find("1.5, 2, 3", true), "not decimals");
		assertNull(WaypointShare.find("100, 900, 3", true), "not a height");
	}

	@Test
	void theClickCommandCarriesEverything() {
		WaypointShare.Found f = new WaypointShare.Found("My base", 1, 2, 3, null);
		String command = WaypointShare.command(f, "minecraft:overworld", "x");
		assertEquals("flintwaypoint add minecraft:overworld 1 2 3 My base", command);
		WaypointShare.Found back = WaypointShare.parseCommand("/" + command);
		assertNotNull(back);
		assertEquals("My base", back.name());
		assertEquals("minecraft:overworld", back.dimension());
		assertEquals(3, back.z());
		assertNull(WaypointShare.parseCommand("tp @s 1 2 3"));
	}
}

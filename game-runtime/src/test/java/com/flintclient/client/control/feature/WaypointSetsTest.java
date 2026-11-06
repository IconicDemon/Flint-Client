package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointSetsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@Test
	void setsArePerDimensionAndTheActiveOneIsKept(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir);
		store.enter("sp:test");
		store.add(new Waypoint("Home", 0, 64, 0, OVERWORLD, 0xFF0000, true));
		store.add(new Waypoint("Mine", 10, 12, 10, OVERWORLD, 0x00FF00, true).withGroup("Caves"));
		store.add(new Waypoint("Fortress", 5, 70, 5, NETHER, 0x0000FF, true).withGroup("Trips"));
		assertEquals(List.of("", "Caves"), store.sets(OVERWORLD));
		assertEquals(List.of("", "Trips"), store.sets(NETHER));
		store.addSet(OVERWORLD, "Farms");
		assertEquals(List.of("", "Farms", "Caves"), store.sets(OVERWORLD));
		store.setActiveSet(OVERWORLD, "Caves");
		assertEquals("Caves", store.activeSet(OVERWORLD));
		assertEquals("", store.activeSet(NETHER));
		Waypoint home = store.all().get(0);
		Waypoint mine = store.all().get(1);
		assertTrue(store.shown(home, true), "all sets");
		assertFalse(store.shown(home, false), "not the active set");
		assertTrue(store.shown(mine, false));
		WaypointStore again = new WaypointStore(dir);
		again.enter("sp:test");
		assertEquals("Caves", again.activeSet(OVERWORLD));
		assertEquals(List.of("", "Farms", "Caves"), again.sets(OVERWORLD));
	}

	@Test
	void renamingAndRemovingASetTakesItsWaypoints(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir);
		store.enter("sp:sets");
		store.add(new Waypoint("A", 0, 0, 0, OVERWORLD, 0, true).withGroup("Old"));
		store.add(new Waypoint("B", 0, 0, 0, OVERWORLD, 0, true));
		store.setActiveSet(OVERWORLD, "Old");
		store.renameSet(OVERWORLD, "Old", "New");
		assertEquals("New", store.all().get(0).group());
		assertEquals("New", store.activeSet(OVERWORLD));
		store.removeSet(OVERWORLD, "New");
		assertEquals(1, store.all().size());
		assertEquals("B", store.all().get(0).name());
		assertEquals("", store.activeSet(OVERWORLD));
	}

	@Test
	void deathWaypointsShowInEverySetAndOnlyTheNewestAreKept(@TempDir Path dir) {
		WaypointStore store = new WaypointStore(dir);
		store.enter("sp:deaths");
		for (int i = 1; i <= 4; i++) {
			store.add(new Waypoint("Death " + i, i, 0, 0, OVERWORLD, 0xE53935, true).withKind(Waypoint.DEATH));
		}
		store.add(new Waypoint("Home", 0, 0, 0, OVERWORLD, 0, true));
		store.setActiveSet(OVERWORLD, "Other");
		assertTrue(store.shown(store.all().get(0), false), "deaths show whatever the set");
		assertEquals(2, store.trimDeaths(2));
		assertEquals(List.of("Death 3", "Death 4", "Home"), store.all().stream().map(Waypoint::name).toList());
		assertEquals(0, store.trimDeaths(0), "0 keeps them all");
	}

	@Test
	void kindAndLettersRoundTrip() {
		Waypoint w = new Waypoint("Spawn", 1, 2, 3, OVERWORLD, 0x123456, true).withKind(Waypoint.DEATH).withInitials("Sp");
		assertEquals(w, WaypointStore.read(WaypointStore.write(w)));
		assertEquals("Sp", w.label());
		assertEquals("S", w.withInitials("").label());
		assertEquals("ab", new Waypoint("x", 0, 0, 0, OVERWORLD, 0, true).withInitials(" a\nb c").initials());
		assertTrue(w.death());
	}
}

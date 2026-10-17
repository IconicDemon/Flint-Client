package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyConflictsTest {
	@Test
	void anotherOnModuleOnTheSameKeyIsAConflict() {
		Layout layout = new Layout("default", "Default");
		ModuleState zoom = layout.module(PlayerMods.Zoom.ID);
		ModuleState waypoints = layout.module(UtilityMods.Waypoints.ID);
		zoom.setEnabled(true);
		waypoints.setEnabled(true);
		zoom.set(PlayerMods.Zoom.KEY, "key.keyboard.b");
		List<String> uses = KeyConflicts.flintUses(layout, "key.keyboard.b", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id());
		assertEquals(List.of("Waypoints: Create waypoint"), uses, "the setting itself isn't counted");
		assertEquals("Also used by Waypoints: Create waypoint", KeyConflicts.warning(uses));
	}

	@Test
	void offModulesAndNoKeyAreNoConflict() {
		Layout layout = new Layout("default", "Default");
		layout.module(UtilityMods.Waypoints.ID).setEnabled(false);
		assertTrue(KeyConflicts.flintUses(layout, "key.keyboard.b", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id()).isEmpty());
		assertTrue(KeyConflicts.flintUses(layout, "", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id()).isEmpty());
		assertNull(KeyConflicts.warning(List.of()));
	}

	@Test
	void theWarningListsEveryUse() {
		assertEquals("Also used by A, B and C", KeyConflicts.warning(List.of("A", "B", "C")));
	}

	@Test
	void oneSettingResetsToItsDefault() {
		ModuleState zoom = new Layout("default", "Default").module(PlayerMods.Zoom.ID);
		assertTrue(zoom.isDefault(PlayerMods.Zoom.LEVEL));
		assertTrue(zoom.set(PlayerMods.Zoom.LEVEL, 9));
		assertTrue(zoom.set(PlayerMods.Zoom.SMOOTH, false));
		assertFalse(zoom.isDefault(PlayerMods.Zoom.LEVEL));
		assertTrue(zoom.resetSetting(PlayerMods.Zoom.LEVEL));
		assertTrue(zoom.isDefault(PlayerMods.Zoom.LEVEL));
		assertFalse(zoom.isDefault(PlayerMods.Zoom.SMOOTH), "the others stay as they were");
		assertFalse(zoom.resetSetting(PlayerMods.Zoom.LEVEL), "already the default: nothing changes");
	}
}

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
		zoom.set(PlayerMods.Zoom.KEY, "key.keyboard.n");
		List<String> uses = KeyConflicts.flintUses(layout, "key.keyboard.n", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id());
		assertEquals(List.of("Waypoints: Create waypoint"), uses, "the setting itself isn't counted");
		assertEquals("Also used by Waypoints: Create waypoint", KeyConflicts.warning(uses));
	}

	@Test
	void offModulesAndNoKeyAreNoConflict() {
		Layout layout = new Layout("default", "Default");
		layout.module(UtilityMods.Waypoints.ID).setEnabled(false);
		assertTrue(KeyConflicts.flintUses(layout, "key.keyboard.n", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id()).isEmpty());
		assertTrue(KeyConflicts.flintUses(layout, "", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id()).isEmpty());
		assertNull(KeyConflicts.warning(List.of()));
	}

	@Test
	void aKeyReadOnlyInChatSharesWithNothingOutsideChat() {
		Layout layout = new Layout("default", "Default");
		ModuleState zoom = layout.module(PlayerMods.Zoom.ID);
		ModuleState chat = layout.module(UtilityMods.Chat.ID);
		zoom.setEnabled(true);
		chat.setEnabled(true);
		zoom.set(PlayerMods.Zoom.KEY, "key.keyboard.f");
		chat.set(UtilityMods.Chat.TAB_KEY, "key.keyboard.f");
		assertEquals(List.of("Chat: Next tab key"),
			KeyConflicts.flintUses(layout, "key.keyboard.f", UtilityMods.Chat.ID, UtilityMods.Chat.SEARCH_KEY.id()), "another chat key does");
		assertEquals(List.of(), KeyConflicts.flintUses(layout, "key.keyboard.f", PlayerMods.Zoom.ID, PlayerMods.Zoom.KEY.id()),
			"chat's keys don't act in play");
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
		assertTrue(zoom.set(PlayerMods.Zoom.RETAIN, true));
		assertFalse(zoom.isDefault(PlayerMods.Zoom.LEVEL));
		assertTrue(zoom.resetSetting(PlayerMods.Zoom.LEVEL));
		assertTrue(zoom.isDefault(PlayerMods.Zoom.LEVEL));
		assertFalse(zoom.isDefault(PlayerMods.Zoom.RETAIN), "the others stay as they were");
		assertFalse(zoom.resetSetting(PlayerMods.Zoom.LEVEL), "already the default: nothing changes");
	}
}

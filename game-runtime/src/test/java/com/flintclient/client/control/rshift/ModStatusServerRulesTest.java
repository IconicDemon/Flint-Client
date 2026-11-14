package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.ServerRules;
import com.flintclient.client.control.model.mods.PlayerMods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A mod card's status (and its settings page's): where the server you play on doesn't allow the mod, it says why, greyed and
 * not pressed, in place of Enabled; back in a world that allows it, it switches again.
 */
class ModStatusServerRulesTest {
	private static Catalog.Mod mod(String moduleId) {
		return Catalog.get().mods().stream().filter(m -> m.module() != null && m.module().id().equals(moduleId)).findFirst().orElseThrow();
	}

	@BeforeEach
	@AfterEach
	void leaveTheWorld() {
		ServerRules.reset();
	}

	@Test
	void onHypixelFreelooksStatusSaysWhyAndIsNotPressed() {
		ModsPage.Status status = new ModsPage.Status(mod(PlayerMods.Freelook.ID));
		assertNull(status.serverReason());
		assertTrue(status.enabled());

		ServerRules.update(true, false, "mc.hypixel.net", null);
		assertEquals("Off on Hypixel: its rules don't allow it", status.serverReason());
		assertFalse(status.enabled(), "not pressed there");
		assertEquals("Off on Hypixel: its rules don't allow it", status.disabledReason(), "the whole reason is its tooltip");
		assertEquals("off on hypixel", status.word());
		assertFalse(status.on(), "off here whatever it is set to");

		ServerRules.reset();
		assertNull(status.serverReason());
		assertTrue(status.enabled(), "it switches again in a world that allows it");
		assertNull(status.disabledReason());
	}

	@Test
	void whatTheServerAllowsKeepsItsSwitch() {
		ModsPage.Status zoom = new ModsPage.Status(mod(PlayerMods.Zoom.ID));
		ServerRules.update(true, false, "mc.hypixel.net", null);
		assertNull(zoom.serverReason());
		assertTrue(zoom.enabled());
	}
}

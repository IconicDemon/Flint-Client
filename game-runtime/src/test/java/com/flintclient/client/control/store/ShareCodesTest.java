package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.mods.PlayerMods;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profile codes: a profile sent as one line of text and made again from it, the same as it was. */
class ShareCodesTest {
	@TempDir
	Path config;

	private LayoutStore store() {
		return new LayoutStore(config, new ControlSettings(config, JsonStoreTest.CLOCK), JsonStoreTest.CLOCK);
	}

	@Test
	void aCodeMakesTheSameProfileOnAnotherFlint() {
		LayoutStore mine = store();
		Layout pvp = mine.active();
		pvp.setName("PvP");
		pvp.module(ModuleId.FPS).setEnabled(true);
		pvp.module(ModuleId.FPS).setPlacement(HudPlacement.of(Anchor.TOP_RIGHT, 12, 30));
		pvp.module(PlayerMods.Zoom.ID).set(PlayerMods.Zoom.LEVEL, 9);
		String code = mine.shareCode(mine.activeId());
		assertNotNull(code);
		assertTrue(code.startsWith(ShareCodes.PREFIX));
		assertTrue(code.length() < 2000, "only what differs from a new profile: " + code.length());

		LayoutResult made = store().importCode(code);
		assertTrue(made.succeeded(), String.valueOf(made.error()));
		Layout copy = store().get(made.id());
		assertNotNull(copy);
		assertEquals("PvP", copy.name());
		assertTrue(copy.module(ModuleId.FPS).enabled());
		assertEquals(HudPlacement.of(Anchor.TOP_RIGHT, 12, 30), copy.module(ModuleId.FPS).placement());
		assertEquals(9, copy.module(PlayerMods.Zoom.ID).integer(PlayerMods.Zoom.LEVEL));
		for (var module : pvp.modules()) {
			assertEquals(module.enabled(), copy.module(module.id()).enabled(), module.id());
		}
	}

	@Test
	void sharingTwiceGivesTwoCodesForTheSameProfile() {
		LayoutStore store = store();
		String a = store.shareCode(store.activeId());
		String b = store.shareCode(store.activeId());
		assertNotEquals(a, b);
		assertEquals(ShareCodes.decode(a), ShareCodes.decode(b));
	}

	@Test
	void aCodeSurvivesSpacesAndLineBreaksFromAChat() {
		LayoutStore store = store();
		String code = store.shareCode(store.activeId());
		String chatted = "  " + code.substring(0, 20) + "\n " + code.substring(20) + " ";
		assertEquals(ShareCodes.decode(code), ShareCodes.decode(chatted));
	}

	@Test
	void brokenCodesAreRefusedAndNothingIsMade() {
		LayoutStore store = store();
		String code = store.shareCode(store.activeId());
		for (String text : new String[] {"FLINT-", "FLINT-@@@@", "FLINT-AAAA", code.substring(0, code.length() / 2), "FLINT-" + "A".repeat(200)}) {
			assertNull(ShareCodes.decode(text), text);
			assertFalse(store.importCode(text).succeeded(), text);
		}
		assertEquals(1, store.list().size());
	}
}

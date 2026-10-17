package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.mods.PlayerMods;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiles copied out as text and read back in: the same JSON as a profile's file, never touching the source. */
class LayoutShareTest {
	@TempDir
	Path config;

	private LayoutStore store() {
		return new LayoutStore(config, new ControlSettings(config, JsonStoreTest.CLOCK), JsonStoreTest.CLOCK);
	}

	@Test
	void anExportedProfileImportsAsANewProfileWithTheSameSettings() {
		LayoutStore store = store();
		Layout active = store.active();
		active.module(ModuleId.FPS).setEnabled(true);
		active.module(PlayerMods.Zoom.ID).set(PlayerMods.Zoom.LEVEL, 11);
		String text = store.exportText(store.activeId());
		assertNotNull(text);
		assertFalse(text.contains("\"id\""), "the file id isn't part of what is shared");
		LayoutResult made = store.importText(text);
		assertTrue(made.succeeded(), String.valueOf(made.error()));
		Layout copy = store.get(made.id());
		assertNotNull(copy);
		assertEquals("Default 2", copy.name(), "a taken name is numbered");
		assertTrue(copy.module(ModuleId.FPS).enabled());
		assertEquals(11, copy.module(PlayerMods.Zoom.ID).integer(PlayerMods.Zoom.LEVEL));
		assertEquals(store.activeId(), "default", "importing doesn't switch");
		assertEquals(2, store.list().size());
	}

	@Test
	void textThatIsNotAProfileIsRefusedAndNothingIsMade() {
		LayoutStore store = store();
		for (String text : new String[] {"", "   ", "hello", "[1,2]", "{\"name\":\"x\"}", "{\"modules\":3}"}) {
			LayoutResult result = store.importText(text);
			assertFalse(result.succeeded(), text);
			assertNotNull(result.error());
		}
		assertFalse(store.importText("{\"schema\":99,\"name\":\"Future\",\"modules\":{}}").succeeded(), "a newer Flint's profile");
		assertFalse(store.importText("x".repeat(LayoutStore.MAX_IMPORT_CHARS + 1)).succeeded());
		assertEquals(1, store.list().size());
	}

	@Test
	void anImportedProfileWithAnOddNameGetsAUsableOne() {
		LayoutStore store = store();
		LayoutResult blank = store.importText("{\"name\":\"\",\"modules\":{}}");
		assertTrue(blank.succeeded());
		assertEquals("Imported profile", store.get(blank.id()).name());
		LayoutResult longName = store.importText("{\"name\":\"" + "N".repeat(80) + "\",\"modules\":{}}");
		assertTrue(longName.succeeded());
		assertTrue(store.get(longName.id()).name().length() <= 32);
	}
}

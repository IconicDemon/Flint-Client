package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.Preset;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutStoreTest {
	@TempDir
	Path config;

	private ControlSettings settings;

	private LayoutStore store() {
		settings = new ControlSettings(config, JsonStoreTest.CLOCK);
		return new LayoutStore(config, settings, JsonStoreTest.CLOCK);
	}

	private Path layouts() {
		return config.resolve("flintclient").resolve("layouts");
	}

	private void writeLayout(String id, String json) throws IOException {
		Files.createDirectories(layouts());
		Files.writeString(layouts().resolve(id + ".json"), json, StandardCharsets.UTF_8);
	}

	private List<String> files() throws IOException {
		if (!Files.isDirectory(layouts())) {
			return List.of();
		}
		try (Stream<Path> list = Files.list(layouts())) {
			return list.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private static List<String> names(LayoutStore store) {
		List<String> names = new ArrayList<>();
		for (LayoutInfo info : store.list()) {
			names.add(info.name());
		}
		return names;
	}

	@Test
	void withoutFilesThereIsAnUnsavedDefaultWithNothingOn() throws IOException {
		LayoutStore store = store();
		Layout active = store.active();
		assertEquals("default", active.id());
		assertEquals("Default", active.name());
		assertEquals(0, active.enabledCount());
		assertFalse(store.anyLayoutFileExists());
		assertFalse(store.dirty());
		assertTrue(store.save());
		assertEquals(List.of(), files(), "not written while unchanged");
		assertFalse(settings.dirty(), "nothing to remember about an unsaved default");
		assertTrue(settings.save());
		assertFalse(Files.exists(settings.file()));
		LayoutInfo row = store.list().get(0);
		assertEquals(-1, row.modifiedMillis());
		assertTrue(row.active());
		assertEquals(HudThemeId.FLINT, row.theme());
	}

	@Test
	void theDefaultBecomesAFileOnceChanged() throws IOException {
		LayoutStore store = store();
		store.active().module(ModuleId.FPS).setEnabled(true);
		assertTrue(store.dirty());
		assertTrue(store.dirty("default"));
		assertTrue(store.save());
		assertFalse(store.dirty());
		assertEquals(List.of("default.json"), files());
		assertTrue(store.anyLayoutFileExists());
		assertEquals(JsonStoreTest.CLOCK.millis(), store.list().get(0).modifiedMillis());

		LayoutStore reopened = store();
		assertTrue(reopened.active().module(ModuleId.FPS).enabled(), "read back after a restart");
		assertEquals(store.active(), reopened.active());
	}

	@Test
	void createFromTheCurrentLayoutOrAPreset() throws IOException {
		LayoutStore store = store();
		store.active().module(ModuleId.CPS).setEnabled(true);
		LayoutResult copy = store.create("  My PvP  ", null, null);
		assertTrue(copy.succeeded());
		assertEquals("my-pvp", copy.id());
		Layout created = store.get("my-pvp");
		assertNotNull(created);
		assertEquals("My PvP", created.name(), "trimmed");
		assertTrue(created.module(ModuleId.CPS).enabled(), "a copy of the current layout");
		assertNotSame(store.active(), created);
		assertEquals("default", store.activeId(), "creating doesn't switch");
		assertTrue(files().contains("my-pvp.json"), "written at once");

		LayoutResult preset = store.create("Balanced", Preset.BALANCED, HudThemeId.MINIMAL);
		Layout balanced = store.get(preset.id());
		assertEquals(6, balanced.enabledCount());
		assertEquals(HudThemeId.MINIMAL, balanced.appearance().theme(), "the chosen style");
		assertEquals(HudThemeId.FLINT, store.get(store.create("Plain", Preset.PVP, null).id()).appearance().theme(),
			"the preset's own style when none is chosen");
		assertEquals(HudThemeId.CLASSIC, store.get(store.create("Classic copy", null, HudThemeId.CLASSIC).id()).appearance().theme());
	}

	@Test
	void namesAreValidatedAndIdsKeptUnique() {
		LayoutStore store = store();
		assertEquals("Enter a name", store.create("   ", null, null).error());
		assertEquals("Use 32 characters or fewer", store.create("x".repeat(33), null, null).error());
		assertTrue(store.create("PvP", null, null).succeeded());
		assertEquals("Another layout is already called \"PvP\"", store.create("pvp", null, null).error());
		assertEquals("pvp-2", store.create("PvP!", null, null).id(), "different name, same slug");
		assertEquals("layout", store.create("★", null, null).id());
		assertEquals("Another layout is already called \"Default\"", store.create("DEFAULT", null, null).error(),
			"the unsaved default counts too");
	}

	@Test
	void renameChecksTheNameAndKeepsTheId() throws IOException {
		LayoutStore store = store();
		String id = store.create("Ranked", null, null).id();
		store.create("Casual", null, null);
		assertEquals("Another layout is already called \"Casual\"", store.rename(id, " casual ").error());
		assertEquals("Enter a name", store.rename(id, "").error());
		assertEquals("Use 32 characters or fewer", store.rename(id, "r".repeat(40)).error());
		assertTrue(store.rename(id, "RANKED").succeeded(), "changing only the case is fine");
		assertTrue(store.rename(id, "Ranked Nights").succeeded());
		assertEquals("Ranked Nights", store.get(id).name());
		assertEquals("ranked", id);
		JsonObject saved = JsonParser.parseString(Files.readString(layouts().resolve("ranked.json"))).getAsJsonObject();
		assertEquals("Ranked Nights", saved.get("name").getAsString(), "saved at once");
		assertEquals("That layout no longer exists.", store.rename("nope", "X").error());
	}

	@Test
	void duplicateMakesANumberedCopy() {
		LayoutStore store = store();
		String id = store.create("Ranked", Preset.PVP, null).id();
		LayoutResult first = store.duplicate(id);
		LayoutResult second = store.duplicate(id);
		assertEquals("Ranked copy", store.get(first.id()).name());
		assertEquals("Ranked copy 2", store.get(second.id()).name());
		assertEquals("ranked-copy", first.id());
		assertEquals("ranked-copy-2", second.id());
		assertEquals(store.get(id).modules(), store.get(first.id()).modules(), "same modules and settings");
		store.get(first.id()).module(ModuleId.FPS).setEnabled(false);
		assertTrue(store.get(id).module(ModuleId.FPS).enabled(), "a deep copy");
	}

	@Test
	void deleteNeverTheLastNorTheActiveLayout() throws IOException {
		LayoutStore store = store();
		assertEquals("You need at least one layout.", store.delete("default").error());
		String other = store.create("Other", null, null).id();
		assertEquals("Switch to another layout before deleting this one.", store.delete("default").error());
		assertTrue(store.switchTo(other).succeeded());
		assertTrue(store.delete("default").succeeded(), "the unsaved default can go once another is in use");
		assertEquals(List.of("Other"), names(store));
		assertEquals("You need at least one layout.", store.delete(other).error());
		String third = store.create("Third", null, null).id();
		assertTrue(files().contains("third.json"));
		assertTrue(store.delete(third).succeeded());
		assertFalse(files().contains("third.json"), "the file is removed");
		assertNull(store.get(third));
		assertEquals("That layout no longer exists.", store.delete(third).error());
	}

	@Test
	void switchingAppliesAtOnceAndIsRemembered() throws IOException {
		LayoutStore store = store();
		String pvp = store.create("PvP", Preset.PVP, null).id();
		int switches = store.switchCount();
		Layout before = store.active();
		assertTrue(store.switchTo(pvp).succeeded());
		assertEquals(pvp, store.activeId());
		assertNotSame(before, store.active());
		assertEquals(switches + 1, store.switchCount(), "the HUD knows to rebuild");
		assertEquals(pvp, settings.activeLayout());
		assertFalse(settings.dirty(), "switching saves the settings");
		assertEquals("That layout no longer exists.", store.switchTo("gone").error());

		LayoutStore reopened = store();
		assertEquals(pvp, reopened.activeId(), "the active layout survives a restart");
		assertEquals(6, reopened.active().enabledCount());
	}

	@Test
	void switchingSavesPendingChanges() throws IOException {
		LayoutStore store = store();
		String a = store.create("A", null, null).id();
		String b = store.create("B", null, null).id();
		store.switchTo(a);
		store.active().module(ModuleId.PING).setEnabled(true);
		assertTrue(store.dirty(a));
		assertFalse(store.dirty(b), "dirty tracking is per layout");
		store.switchTo(b);
		assertFalse(store.dirty(a));
		JsonObject saved = JsonParser.parseString(Files.readString(layouts().resolve("a.json"))).getAsJsonObject();
		assertTrue(saved.getAsJsonObject("modules").getAsJsonObject("ping").get("enabled").getAsBoolean());
	}

	@Test
	void theFirstLayoutByNameIsUsedWhenTheRememberedOneIsMissing() throws IOException {
		writeLayout("zeta", "{\"schema\": 1, \"name\": \"Zeta\"}");
		writeLayout("alpha", "{\"schema\": 1, \"name\": \"Alpha\"}");
		LayoutStore store = store();
		assertEquals("alpha", store.activeId());
		assertEquals(List.of("Alpha", "Zeta"), names(store), "listed by name");
		assertTrue(store.anyLayoutFileExists());
	}

	@Test
	void theActiveLayoutSurvivesARestartOnceTheDefaultIsWritten() throws IOException {
		LayoutStore store = store();
		store.active().module(ModuleId.FPS).setEnabled(true);
		String copy = store.duplicate("default").id();
		assertTrue(store.rename(copy, "Aardvark").succeeded());
		assertTrue(store.save());
		assertEquals("default", settings.activeLayout(), "remembered when the default became a file");
		assertFalse(settings.dirty(), "and saved with it");
		LayoutStore reopened = store();
		assertEquals("default", reopened.activeId(), "not the first layout by name");
		assertTrue(reopened.active().module(ModuleId.FPS).enabled());
	}

	@Test
	void aFallbackChoiceIsRemembered() throws IOException {
		writeLayout("zeta", "{\"schema\": 1, \"name\": \"Zeta\"}");
		writeLayout("beta", "{\"schema\": 1, \"name\": \"Beta\"}");
		LayoutStore store = store();
		store.load();
		assertEquals("beta", store.activeId());
		assertEquals("beta", settings.activeLayout(), "remembered in memory");
		assertTrue(settings.save(), "saved with the settings, as at closing");
		writeLayout("alpha", "{\"schema\": 1, \"name\": \"Alpha\"}");
		assertEquals("beta", store().activeId(), "a layout that sorts first doesn't take over");
	}

	@Test
	void undoingAChangeLeavesNothingToSave() {
		LayoutStore store = store();
		String id = store.create("A", Preset.MINIMAL, null).id();
		store.switchTo(id);
		Layout layout = store.active();
		layout.module(ModuleId.PING).setEnabled(true);
		assertTrue(store.dirty(id));
		layout.module(ModuleId.PING).setEnabled(false);
		assertFalse(store.dirty(id), "back to what the file says");
		Layout snapshot = layout.copy();
		layout.module(ModuleId.FPS).setPlacement(layout.module(ModuleId.FPS).placement().moved(10, 10));
		assertTrue(store.dirty(id));
		layout.restore(snapshot);
		assertFalse(store.dirty(id), "the HUD editor's Cancel leaves nothing to write");
		assertFalse(store.dirty());
	}

	@Test
	void aLayoutThatIsNotUtf8IsDamagedAndDeletingItKeepsACopy() throws IOException {
		Files.createDirectories(layouts());
		byte[] latin1 = "{\"schema\": 1, \"name\": \"Café\"}".getBytes(StandardCharsets.ISO_8859_1);
		Files.write(layouts().resolve("cafe.json"), latin1);
		writeLayout("other", "{\"schema\": 1, \"name\": \"Other\"}");
		LayoutStore store = store();
		LayoutInfo row = store.list().get(0);
		assertEquals("cafe", row.id());
		assertTrue(row.damaged());
		assertEquals("cafe.json couldn't be read. Flint saved a copy as cafe.damaged-20261015-101500.json; this layout can only be "
			+ "deleted.", row.notice());
		assertTrue(store.delete("cafe").succeeded());
		assertEquals(List.of("cafe.damaged-20261015-101500.json", "other.json"), files());
		assertArrayEquals(latin1, Files.readAllBytes(layouts().resolve("cafe.damaged-20261015-101500.json")), "nothing lost");
	}

	@Test
	void anUnreadableLayoutIsDeletedOnlyOnceACopyIsKept() throws IOException {
		assumeTrue(layouts().getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
		writeLayout("locked", "{\"schema\": 1, \"name\": \"Locked\"}");
		writeLayout("fine", "{\"schema\": 1, \"name\": \"Fine\"}");
		Path locked = layouts().resolve("locked.json");
		Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
		try {
			assumeFalse(Files.isReadable(locked), "permissions are not enforced here");
			LayoutStore store = store();
			LayoutInfo row = store.list().get(1);
			assertEquals("locked", row.id());
			assertTrue(row.damaged());
			assertTrue(row.readOnly());
			assertEquals("Flint couldn't keep a copy of this file, so it wasn't deleted.", store.delete("locked").error());
			assertTrue(Files.exists(locked), "still there");
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
			assertTrue(store.delete("locked").succeeded(), "readable again: copied, then deleted");
			assertEquals(List.of("fine.json", "locked.damaged-20261015-101500.json"), files());
		} finally {
			if (Files.exists(locked)) {
				Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-r--r--"));
			}
		}
	}

	@Test
	void aLayoutFromANewerFlintCanBeDeletedOnPurpose() throws IOException {
		writeLayout("future", "{\"schema\": 2, \"name\": \"Future\"}");
		writeLayout("fine", "{\"schema\": 1, \"name\": \"Fine\"}");
		LayoutStore store = store();
		assertEquals("fine", store.activeId());
		assertTrue(store.delete("future").succeeded());
		assertEquals(List.of("fine.json"), files());
	}

	@Test
	void aRenameThatCantBeSavedChangesNothing() throws IOException {
		assumeTrue(config.getFileSystem().supportedFileAttributeViews().contains("posix"), "needs POSIX permissions");
		LayoutStore store = store();
		String id = store.create("Ranked", Preset.PVP, null).id();
		Files.setPosixFilePermissions(layouts(), PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeFalse(Files.isWritable(layouts()), "permissions are not enforced here");
			assertEquals("Couldn't save the layout.", store.rename(id, "Casual").error());
			assertEquals("Ranked", store.get(id).name(), "the old name is back");
			assertFalse(store.dirty(id));
			assertNull(store.nameError("Casual", null), "the new name is still free");
		} finally {
			Files.setPosixFilePermissions(layouts(), PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		JsonObject saved = JsonParser.parseString(Files.readString(layouts().resolve(id + ".json"))).getAsJsonObject();
		assertEquals("Ranked", saved.get("name").getAsString());
	}

	@Test
	void aDamagedLayoutIsListedNeverUsedAndNeverOverwritten() throws IOException {
		String broken = "{\"schema\": 1, \"name\": \"Broken\", \"modules\": {";
		writeLayout("broken", broken);
		writeLayout("fine", "{\"schema\": 1, \"name\": \"Fine\"}");
		LayoutStore store = store();
		LayoutInfo row = store.list().get(0);
		assertEquals("broken", row.id());
		assertEquals("broken", row.name(), "no readable name: the id");
		assertTrue(row.damaged());
		assertEquals("broken.json couldn't be read. Flint saved a copy as broken.damaged-20261015-101500.json; this layout can only be "
			+ "deleted.", row.notice());
		assertEquals("fine", store.activeId());
		assertFalse(store.switchTo("broken").succeeded());
		assertFalse(store.rename("broken", "Fixed").succeeded());
		assertFalse(store.duplicate("broken").succeeded());
		assertNull(store.get("broken"));
		store.active().module(ModuleId.FPS).setEnabled(true);
		assertTrue(store.save());
		assertEquals(broken, Files.readString(layouts().resolve("broken.json")), "left exactly as it was");
		assertTrue(files().contains("broken.damaged-20261015-101500.json"));
		assertTrue(store.delete("broken").succeeded(), "it can be deleted");
		assertTrue(files().contains("broken.damaged-20261015-101500.json"), "the kept copy stays");
		assertEquals(List.of("Fine"), names(store));
	}

	@Test
	void anOnlyDamagedLayoutStillCountsForOnboardingButTheDefaultIsUsed() throws IOException {
		writeLayout("broken", "nope");
		LayoutStore store = store();
		assertTrue(store.anyLayoutFileExists());
		assertEquals("default", store.activeId());
		assertEquals(2, store.list().size());
	}

	@Test
	void aLayoutFromANewerFlintIsUsedButNeverWritten() throws IOException {
		String newer = "{\"schema\": 2, \"name\": \"Future\", \"modules\": {\"fps\": {\"enabled\": true}}, \"widgets\": []}";
		writeLayout("future", newer);
		LayoutStore store = store();
		assertEquals("future", store.activeId());
		assertTrue(store.active().module(ModuleId.FPS).enabled());
		LayoutInfo row = store.list().get(0);
		assertTrue(row.readOnly());
		assertFalse(row.damaged());
		assertEquals("Saved by a newer Flint version; changes won't be saved.", row.notice());
		store.active().module(ModuleId.FPS).set(ModuleSettings.Fps.SHOW_LOW, true);
		assertFalse(store.save());
		assertFalse(store.rename("future", "Past").succeeded());
		assertEquals(newer, Files.readString(layouts().resolve("future.json")));
	}

	@Test
	void strayFilesAreIgnored() throws IOException {
		writeLayout("Not An Id", "{}");
		writeLayout("fine.damaged-20260101-000000", "{}");
		writeLayout("fine", "{\"name\": \"Fine\"}");
		Files.writeString(layouts().resolve("fine.json.tmp"), "{}");
		LayoutStore store = store();
		assertEquals(List.of("Fine"), names(store));
	}

	@Test
	void savedFilesAreSelfContained() throws IOException {
		LayoutStore store = store();
		String id = store.create("Portable", Preset.MINIMAL, null).id();
		Path file = layouts().resolve(id + ".json");
		JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		assertEquals(store.get(id), LayoutCodec.read(json, id), "the file alone rebuilds the layout");
		assertEquals(List.of("schema", "id", "name", "appearance", "modules"), List.copyOf(json.keySet()));
		assertFalse(Files.readString(file).contains("key_"), "no key bindings");
	}

	@Test
	void theSameStoreKeepsTheSameActiveObject() {
		LayoutStore store = store();
		assertSame(store.active(), store.active());
	}
}

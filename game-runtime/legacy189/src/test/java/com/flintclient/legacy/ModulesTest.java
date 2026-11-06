package com.flintclient.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.Catalog;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.shared.Engine189;
import com.flintclient.legacy.shared.Migration189;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The 1.8.9 mods as backends of the shared modules: each binds only its own shared module's settings (or fields of its HUD look),
 * its card comes from the Right Shift catalog, and the shared menu on 1.8.9 ({@link Engine189}) lists exactly these mods, each with
 * only the rows its backend honours.
 */
class ModulesTest {
	private static Modules modules;

	@BeforeAll
	static void make() {
		modules = new Modules();
	}

	@Test
	void eachMod189IsTheBackendOfOneSharedModule() {
		Set<String> ids = new HashSet<String>();
		for (Module m : modules.all()) {
			assertNotNull(m.descriptor, m.id);
			assertTrue(ids.add(m.id), "two backends of " + m.id);
		}
		Set<String> expected = new HashSet<String>(Migration189.SHARED_IDS.values());
		// Mods 1.8.9 got after its own settings file was retired: nothing of theirs to carry over.
		expected.add(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID);
		expected.add(com.flintclient.client.control.model.mods.MoreMods.UhcOverlay.ID);
		expected.add(com.flintclient.client.control.model.mods.HudMods.TntCountdown.ID);
		expected.add(com.flintclient.client.control.model.mods.MoreMods.Subtitles.ID);
		assertEquals(expected, ids);
		assertEquals(28, modules.all().size());
		assertEquals(modules.byId("fps"), modules.byId("fps"));
		assertEquals("animations", modules.byId("hitcolor").id, "an old 1.8.9 id still finds its mod");
	}

	@Test
	void everyBoundSettingIsItsModulesOrALookField() {
		List<Object> look = Arrays.asList((Object[]) Appearance.FIELDS);
		for (Module m : modules.all()) {
			for (Setting<?> s : m.settings()) {
				boolean own = m.descriptor.settings().contains(s.shared);
				assertTrue(own || look.contains(s.shared), m.id + " binds " + s.id + ", not one of its settings");
			}
			assertTrue(m.settings().contains(m.toggleKey), m.id + " has its toggle key");
		}
	}

	@Test
	void cardNamesComeFromTheCatalog() {
		for (Module m : modules.all()) {
			assertNotNull(Catalog.get().modFor(m.id), m.id + " has a card");
			assertFalse(m.name.isEmpty());
		}
		assertEquals("Custom Crosshair", modules.crosshair.name, "the catalog's card name");
	}

	@Test
	void theMenuListsExactlyThe189ModsWithTheRowsTheirBackendsHonour() {
		Catalog all = Catalog.get();
		Catalog shown = all.on(new Engine189(modules));
		Set<String> listed = new HashSet<String>();
		for (Catalog.Mod mod : shown.mods()) {
			assertNotNull(mod.module(), mod.key() + " is a Flint module");
			Module backend = modules.byId(mod.module().id());
			assertNotNull(backend, mod.key() + " has a 1.8.9 backend");
			listed.add(backend.id);
			assertFalse(mod.sections().isEmpty(), mod.key() + " has rows");
			for (Catalog.Section section : mod.sections()) {
				assertFalse(section.rows().isEmpty(), mod.key() + ": empty section " + section.title());
				for (Catalog.Row row : section.rows()) {
					assertTrue(row.setting() == null || row.module() == null || modules.byId(row.module().id()).honours(row.setting()),
						mod.key() + ": " + row.label() + " isn't honoured on 1.8.9");
				}
			}
		}
		Set<String> ids = new HashSet<String>();
		for (Module m : modules.all()) {
			ids.add(m.id);
		}
		assertEquals(ids, listed, "every 1.8.9 mod has its card, and no other mod is listed");
		assertTrue(all.mods().size() > shown.mods().size(), "mods without a 1.8.9 backend are hidden");
	}

	@Test
	void flintsOwnPageHasTheClientSettings189Honours() {
		Catalog shown = Catalog.get().on(new Engine189(modules));
		Set<Object> rows = new HashSet<Object>();
		for (Catalog.Page page : shown.pages()) {
			for (Catalog.Section section : page.sections()) {
				for (Catalog.Row row : section.rows()) {
					if (row.global() && row.setting() != null && !row.minecraft()) {
						assertNotNull(modules.general.bound(row.setting()), row.label() + " is honoured on 1.8.9");
						rows.add(row.setting());
					}
				}
			}
		}
		assertTrue(rows.containsAll(Arrays.asList(GameOptions.MENU_KEY, GameOptions.MENU_SCALE, GameOptions.CLOCK_24H, GameOptions.MOD_KEYS)),
			rows.toString());
		// Each is kept in control-center.json, but the menu's key: Minecraft's binding, as on every line.
		for (Setting<?> s : modules.general.settings()) {
			assertTrue(s.shared == GameOptions.MENU_KEY || GameOptions.ALL.contains(s.shared), s.id + " is a stored client setting");
		}
	}
}

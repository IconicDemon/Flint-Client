package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.SettingDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The shipped catalog: the reference's structure, and every Flint setting and module reachable exactly once. */
class CatalogTest {
	private final Catalog catalog = Catalog.get();

	private List<Catalog.Section> everySection() {
		List<Catalog.Section> all = new ArrayList<>();
		catalog.mods().forEach(m -> all.addAll(m.sections()));
		catalog.pages().forEach(p -> all.addAll(p.sections()));
		return all;
	}

	@Test
	void theTabsAndCategoriesAreTheReferences() {
		assertEquals(List.of("General", "Performance", "Cosmetic", "Graphics", "Social"), catalog.pages().stream().map(Catalog.Page::name).toList());
		// The reference's 94, less Voice and Emotes (other companies' services), and Music Player.
		assertEquals(93, catalog.mods().stream().filter(m -> !m.extra()).count());
		assertEquals(catalog.mods().size(), catalog.mods().stream().map(Catalog.Mod::key).distinct().count());
		assertEquals("Animations", catalog.mods().stream().filter(m -> !m.extra()).findFirst().orElseThrow().name());
		// Sorted by the reference's key: "Custom Crosshair" comes before "Cull Logs" (crosshair < culllogs).
		List<String> names = catalog.mods().stream().map(Catalog.Mod::name).toList();
		assertTrue(names.indexOf("Custom Crosshair") < names.indexOf("Cull Logs"));
		assertTrue(catalog.mod("keystrokes").in(Catalog.Category.HUD));
		assertTrue(catalog.mod("armorstatus").in(Catalog.Category.PVP));
		assertTrue(catalog.mod("hypixel").in(Catalog.Category.HYPIXEL));
		assertFalse(catalog.mod("zoom").in(Catalog.Category.HUD));
	}

	@Test
	void musicPlayerClosesTheListAsTheNewHudMod() {
		Catalog.Mod music = catalog.mods().getLast();
		assertEquals("musicplayer", music.key(), "the last card, after the alphabetical ones");
		assertEquals("Music Player", music.name());
		assertSame(com.flintclient.client.control.model.ModuleRegistry.get(com.flintclient.client.control.model.mods.MusicMods.MusicPlayer.ID),
			music.module());
		assertTrue(music.in(Catalog.Category.HUD));
		assertTrue(music.in(Catalog.Category.NEW));
		assertEquals(List.of("Mod", "General", "Display", "Colors", "Motion", "Controls", "Style"),
			music.sections().stream().map(Catalog.Section::title).toList().subList(0, 7));
		// Whatever a line runs, it stays last there (1.8.9 lists only its mods, Music Player among them).
		Catalog only = catalog.on(new Catalog.Engine() {
			@Override
			public boolean runs(com.flintclient.client.control.model.ModuleDescriptor module) {
				return module.id().equals("fps") || module.id().equals("zoom") || module.id().equals("music_player");
			}

			@Override
			public boolean shows(Catalog.Row row) {
				return true;
			}
		});
		assertEquals(List.of("fps", "zoom", "musicplayer"), only.mods().stream().map(Catalog.Mod::key).toList());
	}

	@Test
	void aComingModsRowsDontWorkSoTheirSettingsStayOnTheirOwnCards() {
		for (Catalog.Mod m : catalog.mods()) {
			if (m.module() == null) {
				m.sections().forEach(s -> s.rows().forEach(r -> assertFalse(r.inFlint(), m.name() + ": " + r.label())));
			}
		}
		// Animations names Damage Tint's switch and Hurt Cam's strength; both keep their own cards.
		assertNotNull(catalog.mod("damage_tint").module());
		assertTrue(catalog.mod("hurt_cam").sections().stream().anyMatch(s -> s.rows().stream().anyMatch(r -> r.setting() != null)));
	}

	@Test
	void everyFlintSettingAppearsExactlyOnce() {
		Map<SettingDescriptor<?>, Integer> seen = new IdentityHashMap<>();
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				if (r.setting() != null) {
					seen.merge(r.setting(), 1, Integer::sum);
				}
			}
		}
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			for (SettingDescriptor<?> setting : module.settings()) {
				assertEquals(1, seen.getOrDefault(setting, 0), module.id() + "/" + setting.id());
			}
		}
		for (SettingDescriptor<?> global : Catalog.GLOBALS) {
			// The Theme page shows its sounds; General lists them only where a reference row names one.
			int expected = Catalog.ON_THEME_PAGE.contains(global) && !seen.containsKey(global) ? 0 : 1;
			assertEquals(expected, seen.getOrDefault(global, 0), "global/" + global.id());
		}
	}

	@Test
	void theGraphicsTabMirrorsEachOfMinecraftsVideoOptionsOnce() {
		Catalog.Page graphics = catalog.pages().stream().filter(p -> p.key().equals("graphics")).findFirst().orElseThrow();
		assertEquals(List.of("Display", "Quality", "Effects"), graphics.sections().stream().map(Catalog.Section::title).toList());
		// Performance is a tab of its own (frame rate, distances, the inactive game), every row a real setting.
		Catalog.Page performance = catalog.pages().stream().filter(p -> p.key().equals("performance")).findFirst().orElseThrow();
		assertNull(performance.unavailable());
		assertEquals(List.of("Frame Rate", "World", "Inactive Game"), performance.sections().stream().map(Catalog.Section::title).toList());
		performance.sections().forEach(s -> s.rows().forEach(r -> assertTrue(r.global() && r.inFlint(), r.label())));
		Map<SettingDescriptor<?>, Integer> seen = new IdentityHashMap<>();
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				if (r.minecraft()) {
					seen.merge(r.setting(), 1, Integer::sum);
				}
			}
		}
		for (SettingDescriptor<?> option : com.flintclient.client.control.model.VideoOptions.ALL) {
			assertEquals(1, seen.getOrDefault(option, 0), option.id());
		}
		graphics.sections().forEach(s -> s.rows().forEach(r -> assertTrue(r.global(), r.label())));
	}

	@Test
	void theGeneralTabHasFlintsOwnSectionsAndItsButtonsAndShortcutsWork() {
		Catalog.Page general = catalog.pages().get(0);
		List<String> titles = general.sections().stream().map(Catalog.Section::title).toList();
		assertEquals(List.of("Menu", "Gameplay", "HUD", "Social", "Folders"), titles.subList(0, 5));
		List<Catalog.Row> rows = general.sections().stream().flatMap(s -> s.rows().stream()).toList();
		Catalog.Row discord = rows.stream().filter(r -> Catalog.LINK.equals(r.action())).findFirst().orElseThrow();
		assertEquals("discord", discord.module().id());
		// A shortcut is not the mod's switch: the Discord card keeps that.
		assertFalse(discord.toggle());
		assertTrue(rows.stream().anyMatch(r -> "screenshots".equals(r.action()) && r.inFlint()));
		// Nothing on General is one of Minecraft's options (those are on Graphics).
		assertFalse(rows.stream().anyMatch(Catalog.Row::minecraft));
	}

	@Test
	void everyFlintModuleIsSwitchedFromExactlyOnePlace() {
		Map<String, Integer> switches = new HashMap<>();
		for (Catalog.Mod m : catalog.mods()) {
			if (m.module() != null) {
				switches.merge(m.module().id(), 1, Integer::sum);
			}
		}
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				if (r.toggle()) {
					switches.merge(r.module().id(), 1, Integer::sum);
				}
			}
		}
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			// A module Flint keeps switched off isn't listed.
			assertEquals(module.status().usable() ? 1 : 0, switches.getOrDefault(module.id(), 0), module.id());
		}
	}

	@Test
	void everyHudLookFieldIsReachableExactlyOnce() {
		Map<String, Integer> looks = new HashMap<>();
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				if (r.look()) {
					looks.merge((r.module() != null ? r.module().id() : "profile") + "/" + r.setting().id(), 1, Integer::sum);
				}
			}
		}
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			for (SettingDescriptor<?> field : com.flintclient.client.control.model.Appearance.FIELDS) {
				assertEquals(Catalog.panelLook(module) ? 1 : 0, looks.getOrDefault(module.id() + "/" + field.id(), 0), module.id() + " " + field.id());
			}
		}
		for (SettingDescriptor<?> field : com.flintclient.client.control.model.Appearance.FIELDS) {
			int expected = field == com.flintclient.client.control.model.Appearance.SHOW_LABEL ? 0 : 1;
			assertEquals(expected, looks.getOrDefault("profile/" + field.id(), 0), "profile " + field.id());
		}
		// Coordinates' keys colour is Flint's accent: the reference row drives it.
		assertTrue(catalog.mod("coordinates").sections().stream().flatMap(s -> s.rows().stream())
			.anyMatch(r -> r.label().equals("Keys Color") && r.look() && r.setting().id().equals("accentColor")));
	}

	@Test
	void boundRowsUseTheSameKindOfControl() {
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				if (r.toggle()) {
					assertEquals("toggle", r.kind(), r.label());
				} else if (r.setting() != null) {
					assertEquals(Catalog.kindOf(r.setting()), r.kind(), r.label() + " -> " + r.setting().id());
				}
			}
		}
	}

	@Test
	void aWorkingModOrTabListsOnlyWhatFlintRuns() {
		for (Catalog.Mod m : catalog.mods()) {
			if (m.module() != null) {
				for (Catalog.Section s : m.sections()) {
					assertFalse(s.rows().isEmpty(), m.name() + ": " + s.title());
					s.rows().forEach(r -> assertTrue(r.inFlint(), m.name() + ": " + r.label()));
				}
			}
		}
		for (Catalog.Page p : catalog.pages()) {
			if (p.unavailable() == null) {
				p.sections().forEach(s -> s.rows().forEach(r -> assertTrue(r.inFlint(), p.name() + ": " + r.label())));
			}
		}
		Catalog.Mod fps = catalog.mod("fps");
		assertNotNull(fps.module());
		// FPS's reference rows stay in the reference's order, with Flint's other FPS settings after them.
		assertEquals(Catalog.MORE, fps.sections().get(fps.sections().size() - 1).title().substring(0, Catalog.MORE.length()));
	}

	@Test
	void unavailableThingsHaveNoControlAndSayWhy() {
		// A mod that needs another company's service isn't listed at all.
		assertNull(catalog.mod("voice"));
		assertNull(catalog.mod("emotes"));
		// Tier Tagger is Flint's own port of TierTagger.
		assertNotNull(catalog.mod("tiertagger").module());
		Catalog.Page social = catalog.pages().stream().filter(p -> p.key().equals("social")).findFirst().orElseThrow();
		assertNotNull(social.unavailable());
		for (Catalog.Section s : everySection()) {
			for (Catalog.Row r : s.rows()) {
				assertFalse(r.unavailable() != null && r.inFlint(), r.label() + " is unavailable but has a control");
			}
		}
		catalog.mods().forEach(m -> assertNull(m.unavailable(), m.name()));
	}

	@Test
	void labelsNeverNameTheReferenceClient() {
		for (Catalog.Mod m : catalog.mods()) {
			assertFalse(m.name().contains("Dawn"), m.name());
		}
		for (Catalog.Section s : everySection()) {
			assertFalse(s.title().contains("Dawn"), s.title());
			for (Catalog.Row r : s.rows()) {
				assertFalse(r.label().contains("Dawn"), r.label());
			}
		}
	}
}

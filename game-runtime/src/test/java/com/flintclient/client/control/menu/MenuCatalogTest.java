package com.flintclient.client.control.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.menu.MenuCatalog.Category;
import com.flintclient.client.control.menu.MenuCatalog.Entry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The catalogue matches the owner's reference (order, badges, chip counts) and joins every card to a real module. */
class MenuCatalogTest {
	private static final List<String> ORDER = List.of(
		"FPS", "CPS", "View Angles",
		"Toggle Sneak/Sprint", "Zoom", "Height Limit",
		"Armor Status", "Keystrokes", "Coordinates",
		"Day Counter", "Crosshair", "Attack Indicator",
		"Potion Effects", "Direction HUD", "Waypoints",
		"Hit Color", "Scoreboard", "Titles",
		"Item Counter", "Potion Counter", "Ping",
		"Motion Blur", "Pack Organizer", "Chat",
		"Tab Editor", "Nametags", "Shulker Preview",
		"Scrollable Tooltips", "Particle Changer", "Nick Hider",
		"Cooldowns", "Playtime", "Memory Usage",
		"Combo Counter", "Reach Display", "Time Changer",
		"Saturation", "Color Saturation", "Item Physics",
		"TNT Countdown", "3D Skins", "Momentum",
		"Block Outline", "Screenshot Uploader", "FOV Changer",
		"Fog Customizer", "2D Items", "Boss Bar",
		"Freelook", "Menu Blur", "Hitbox",
		"Lighting", "Chunk Borders", "Better Sounds",
		"Hurt Cam", "Tier Tagger", "Damage Tint",
		"Horse Stats", "Overlay", "Light Overlay",
		"Kill Sounds", "Inventory", "GUI Scale",
		"Flint Music");

	private static final Set<String> NEW = Set.of("Height Limit", "Crosshair", "Attack Indicator", "Potion Counter", "Playtime",
		"Tier Tagger", "Overlay", "Light Overlay", "GUI Scale", "Flint Music");

	@Test
	void sixtyFourEntriesInTheReferenceOrder() {
		assertEquals(64, MenuCatalog.ENTRIES.size());
		for (int i = 0; i < ORDER.size(); i++) {
			assertEquals(ORDER.get(i), MenuCatalog.ENTRIES.get(i).name(), "entry " + i);
		}
	}

	@Test
	void badgesMatchTheReference() {
		for (Entry entry : MenuCatalog.ENTRIES) {
			assertEquals(NEW.contains(entry.name()), entry.isNew(), entry.name() + " NEW");
		}
	}

	@Test
	void chipCountsMatchTheReference() {
		assertEquals(64, MenuCatalog.count(Category.ALL));
		assertEquals(10, MenuCatalog.count(Category.NEW));
		assertEquals(23, MenuCatalog.count(Category.HUD));
		assertEquals(18, MenuCatalog.count(Category.PVP));
		assertEquals(22, MenuCatalog.count(Category.VISUAL));
		assertEquals(13, MenuCatalog.count(Category.UTILITY));
	}

	@Test
	void idsAreUniqueAndEveryEntryHasAnIcon() {
		Set<String> ids = new HashSet<>();
		for (Entry entry : MenuCatalog.ENTRIES) {
			assertTrue(ids.add(entry.id()), "duplicate id " + entry.id());
			assertTrue(entry.icon() != null, entry.name() + " icon");
		}
		assertEquals("toggle_sneak_sprint", MenuCatalog.ENTRIES.get(3).id());
		assertEquals("3d_skins", MenuCatalog.ENTRIES.get(40).id());
	}

	@Test
	void everyCardIsARealModuleAndEveryModuleHasOneCard() {
		Set<String> modules = new HashSet<>();
		for (Entry entry : MenuCatalog.ENTRIES) {
			String module = MenuCatalog.moduleOf(entry);
			assertTrue(com.flintclient.client.control.model.ModuleRegistry.find(module) != null, entry.name() + " has no module");
			assertTrue(modules.add(module), "two cards for " + module);
		}
		assertEquals(com.flintclient.client.control.model.ModuleRegistry.size(), modules.size(), "every module has a card");
		assertEquals("armor", MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(6)));
		assertEquals("effects", MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(12)));
		assertEquals("direction", MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(13)));
	}

	@Test
	void searchIsCaseInsensitiveAndCombinesWithTheChip() {
		Entry potion = MenuCatalog.ENTRIES.get(12);
		assertTrue(MenuCatalog.matches(potion, Category.ALL, "POTION"));
		assertTrue(MenuCatalog.matches(potion, Category.HUD, " effects "));
		assertFalse(MenuCatalog.matches(potion, Category.VISUAL, "potion"));
		assertFalse(MenuCatalog.matches(potion, Category.ALL, "zoom"));
		long potions = MenuCatalog.ENTRIES.stream().filter(e -> MenuCatalog.matches(e, Category.ALL, "potion")).count();
		assertEquals(2, potions);
		long newPotions = MenuCatalog.ENTRIES.stream().filter(e -> MenuCatalog.matches(e, Category.NEW, "potion")).count();
		assertEquals(1, newPotions);
	}

	@Test
	void theDeepSearchFindsModulesBySettingSectionOrKeywordAndSaysWhy() {
		Entry zoom = MenuCatalog.ENTRIES.stream().filter(e -> e.id().equals("zoom")).findFirst().orElseThrow();
		assertTrue(MenuCatalog.matchesDeep(zoom, Category.ALL, "zoom"), "by name, as before");
		assertEquals(null, MenuCatalog.settingMatch(zoom, "zoom"), "a name match needs no reason");
		assertTrue(MenuCatalog.matchesDeep(zoom, Category.ALL, "smooth duration"));
		assertEquals("Smooth · Smooth duration", MenuCatalog.settingMatch(zoom, "SMOOTH DURATION"));
		Entry sprint = MenuCatalog.ENTRIES.get(3);
		assertEquals("Keyword: crouch", MenuCatalog.settingMatch(sprint, "crouch"));
		assertFalse(MenuCatalog.matchesDeep(zoom, Category.HUD, "smooth duration"), "the chip still applies");
		assertFalse(MenuCatalog.matchesDeep(zoom, Category.ALL, "no such words anywhere"));
		assertEquals(null, MenuCatalog.settingMatch(zoom, "  "));
	}
}

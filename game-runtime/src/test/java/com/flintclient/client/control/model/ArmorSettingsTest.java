package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.ModuleSettings.Armor;
import com.flintclient.client.control.store.LayoutCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Armor's own settings: the text's size and weight, the item slots, Free placement and how each greys out the rest. */
class ArmorSettingsTest {
	private static ModuleState armor() {
		return new Layout("x", "X").module(ModuleId.ARMOR);
	}

	@Test
	void armorIsAHudModuleTheEditorShowsInMinecraftsLook() {
		ModuleDescriptor descriptor = ModuleRegistry.get(ModuleId.ARMOR);
		assertTrue(descriptor.hud(), "the HUD editor lists and drags it");
		assertTrue(descriptor.drawnByMinecraft(), "no panel or HUD look of Flint's: it draws Minecraft's hotbar slots");
		assertEquals(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 4), descriptor.defaultPlacement(), "where Free starts");
	}

	@Test
	void theTextSizeStaysWithinHalfAndDoubleInFivePercentSteps() {
		ModuleState armor = armor();
		assertEquals(100, armor.integer(Armor.TEXT_SCALE));
		armor.set(Armor.TEXT_SCALE, 10);
		assertEquals(50, armor.integer(Armor.TEXT_SCALE));
		armor.set(Armor.TEXT_SCALE, 999);
		assertEquals(200, armor.integer(Armor.TEXT_SCALE));
		armor.set(Armor.TEXT_SCALE, 77);
		assertEquals(75, armor.integer(Armor.TEXT_SCALE), "on a 5 % step");
		armor.set(Armor.TEXT_SCALE, 125);
		assertEquals(125, armor.integer(Armor.TEXT_SCALE));
	}

	@Test
	void theTextRowsWaitForTextDurability() {
		ModuleState armor = armor();
		for (SettingDescriptor<?> text : new SettingDescriptor<?>[] {Armor.TEXT_POSITION, Armor.TEXT_SCALE, Armor.TEXT_BOLD, Armor.TEXT_SHADOW,
			Armor.TEXT_COLOR_MODE, Armor.TEXT_COLOR}) {
			assertEquals("Only with Numeric or Percentage durability", ModuleSettings.inactiveReason(armor, text), text.id());
		}
		armor.set(Armor.DURABILITY, 1);
		for (SettingDescriptor<?> text : new SettingDescriptor<?>[] {Armor.TEXT_POSITION, Armor.TEXT_SCALE, Armor.TEXT_BOLD, Armor.TEXT_SHADOW,
			Armor.TEXT_COLOR_MODE}) {
			assertNull(ModuleSettings.inactiveReason(armor, text), text.id());
		}
		assertEquals("Only in the Custom text color mode", ModuleSettings.inactiveReason(armor, Armor.TEXT_COLOR));
		armor.set(Armor.TEXT_COLOR_MODE, 1);
		assertNull(ModuleSettings.inactiveReason(armor, Armor.TEXT_COLOR));
	}

	@Test
	void freePlacementGreysOutTheAnchorsDetails() {
		ModuleState armor = armor();
		assertNull(ModuleSettings.inactiveReason(armor, Armor.SIDE));
		assertNull(ModuleSettings.inactiveReason(armor, Armor.OFFSET_X));
		assertNull(ModuleSettings.inactiveReason(armor, Armor.OFFHAND_SLOT), "the Hotbar anchor");
		armor.set(Armor.ANCHOR, Armor.FREE);
		assertEquals("Free: where you drag it in the HUD editor", ModuleSettings.inactiveReason(armor, Armor.SIDE));
		assertEquals("Free: where you drag it in the HUD editor", ModuleSettings.inactiveReason(armor, Armor.OFFSET_X));
		assertEquals("Free: where you drag it in the HUD editor", ModuleSettings.inactiveReason(armor, Armor.OFFSET_Y));
		assertEquals("Only with the Hotbar anchor", ModuleSettings.inactiveReason(armor, Armor.OFFHAND_SLOT));
		assertEquals("Only with the Top Center anchor", ModuleSettings.inactiveReason(armor, Armor.PUSH_BOSSBARS));
		assertNull(ModuleSettings.inactiveReason(armor, Armor.LAYOUT), "the orientation applies wherever it is");
		assertNull(ModuleSettings.inactiveReason(armor, Armor.REVERSED));
	}

	@Test
	void whatMinecraftDoesntHaveIsGreyedOutWithTheReason() {
		// Run on every line: 1.8.9 (no offhand, shields, tridents or maces) and the newer ones (all of them).
		ModuleState armor = armor();
		String missing = com.flintclient.client.compat.McCompat.OFFHAND ? null : "Not in Minecraft 1.8.9";
		for (SettingDescriptor<?> s : new SettingDescriptor<?>[] {Armor.SHIELD, Armor.TRIDENT, Armor.MACE, Armor.OFFHAND_DURABILITY}) {
			assertEquals(missing, ModuleSettings.inactiveReason(armor, s), s.id());
		}
		for (SettingDescriptor<?> s : new SettingDescriptor<?>[] {Armor.SWORD, Armor.PICKAXE, Armor.AXE, Armor.BOW, Armor.MAINHAND_DURABILITY}) {
			assertNull(ModuleSettings.inactiveReason(armor, s), s.id());
		}
	}

	@Test
	void theHudEditorFreesItOnceAndResetTakesItHome() {
		ModuleState armor = armor();
		assertTrue(ModuleSettings.placesItself(armor), "against the hotbar by default");
		assertTrue(ModuleSettings.free(armor));
		assertEquals(Armor.FREE, armor.option(Armor.ANCHOR));
		assertFalse(ModuleSettings.placesItself(armor));
		assertFalse(ModuleSettings.free(armor), "already free");
		// Any other module is placed by its HUD placement already.
		ModuleState fps = new Layout("y", "Y").module(ModuleId.FPS);
		assertFalse(ModuleSettings.placesItself(fps));
		assertFalse(ModuleSettings.free(fps));
		// Choosing an anchor again gives it back to uku's placement; its HUD placement waits for the next time it is Free.
		armor.setPlacement(new HudPlacement(Anchor.TOP_LEFT, 40, 30, 1.5F));
		armor.set(Armor.ANCHOR, Armor.TOP);
		assertTrue(ModuleSettings.placesItself(armor));
		assertEquals(new HudPlacement(Anchor.TOP_LEFT, 40, 30, 1.5F), armor.placement());
		// Reset: back against the hotbar on the left with no offsets, and its placement at the default.
		armor.set(Armor.ANCHOR, Armor.FREE);
		armor.set(Armor.SIDE, 1);
		armor.set(Armor.OFFSET_X, 12);
		armor.set(Armor.OFFSET_Y, -3);
		armor.set(Armor.TEXT_SCALE, 150);
		assertTrue(armor.resetPlacement());
		assertEquals(Armor.HOTBAR, armor.option(Armor.ANCHOR));
		assertEquals(0, armor.option(Armor.SIDE));
		assertEquals(0, armor.integer(Armor.OFFSET_X));
		assertEquals(0, armor.integer(Armor.OFFSET_Y));
		assertEquals(ModuleRegistry.get(ModuleId.ARMOR).defaultPlacement(), armor.placement());
		assertEquals(150, armor.integer(Armor.TEXT_SCALE), "a position reset leaves the look alone");
		assertFalse(armor.resetPlacement(), "already home");
		Layout layout = new Layout("z", "Z");
		ModuleSettings.free(layout.module(ModuleId.ARMOR));
		assertTrue(layout.resetPositions());
		assertTrue(ModuleSettings.placesItself(layout.module(ModuleId.ARMOR)));
	}

	@Test
	void anOldLayoutReadsTheNewSettingsAsTheirDefaults() {
		JsonObject json = JsonParser.parseString("""
			{"schema": 1, "id": "x", "name": "X",
			 "modules": {"armor": {"enabled": true,
			   "settings": {"durabilityDisplay": "numeric", "textColorMode": "custom", "textColor": "#FFAA00", "textShadow": false,
			                "anchor": "top", "side": "right", "offsetX": 6},
			   "hud": {"anchor": "BOTTOM_RIGHT", "x": 4, "y": 4, "scale": 1}}}}
			""").getAsJsonObject();
		ModuleState armor = LayoutCodec.read(json, "x").module(ModuleId.ARMOR);
		assertTrue(armor.enabled());
		assertEquals(1, armor.option(Armor.DURABILITY));
		assertEquals(1, armor.option(Armor.TEXT_COLOR_MODE), "the existing text options are kept");
		assertEquals(0xFFAA00, armor.rgb(Armor.TEXT_COLOR));
		assertFalse(armor.bool(Armor.TEXT_SHADOW));
		assertEquals(Armor.TOP, armor.option(Armor.ANCHOR));
		assertEquals(6, armor.integer(Armor.OFFSET_X));
		assertEquals(100, armor.integer(Armor.TEXT_SCALE), "new: its default");
		assertFalse(armor.bool(Armor.TEXT_BOLD));
		for (BoolSetting item : Armor.ITEMS) {
			assertFalse(armor.bool(item), item.id());
		}
	}

	@Test
	void freePlacementTheTextAndTheItemSlotsSurviveARestart() {
		Layout layout = new Layout("x", "X");
		ModuleState armor = layout.module(ModuleId.ARMOR);
		armor.setEnabled(true);
		ModuleSettings.free(armor);
		armor.setPlacement(new HudPlacement(Anchor.LEFT, 12.5F, -30, 1.25F));
		armor.set(Armor.TEXT_SCALE, 135);
		armor.set(Armor.TEXT_BOLD, true);
		armor.set(Armor.SWORD, true);
		armor.set(Armor.SHIELD, true);
		armor.set(Armor.LAYOUT, Orientation.VERTICAL);
		JsonObject json = LayoutCodec.toJson(layout);
		JsonObject settings = json.getAsJsonObject("modules").getAsJsonObject("armor").getAsJsonObject("settings");
		assertEquals("free", settings.get("anchor").getAsString(), "saved by its key");
		assertEquals(135, settings.get("textScale").getAsInt());
		assertTrue(settings.get("textBold").getAsBoolean());
		assertTrue(settings.get("showSword").getAsBoolean());
		ModuleState read = LayoutCodec.read(json, "x").module(ModuleId.ARMOR);
		assertEquals(armor, read);
		assertEquals(Armor.FREE, read.option(Armor.ANCHOR));
		assertEquals(new HudPlacement(Anchor.LEFT, 12.5F, -30, 1.25F), read.placement());
		// A Flint from before Free reads it as its default anchor (the hotbar) and keeps the key for this one.
		JsonObject older = JsonParser.parseString("{\"schema\": 1, \"id\": \"x\", \"name\": \"X\", \"modules\": {\"armor\": {\"settings\": "
			+ "{\"anchor\": \"somewhere_new\"}}}}").getAsJsonObject();
		assertEquals(Armor.HOTBAR, LayoutCodec.read(older, "x").module(ModuleId.ARMOR).option(Armor.ANCHOR));
	}
}

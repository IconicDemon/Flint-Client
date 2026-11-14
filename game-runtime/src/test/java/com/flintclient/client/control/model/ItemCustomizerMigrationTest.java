package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import com.flintclient.client.control.store.LayoutCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Item Customizer (Flint 1.0.4) took over View Model's id and settings, Animations' swing duration and Item Physics' item
 * scales: a layout saved by an older Flint looks and plays the same after the update, and the older keys leave the file.
 */
class ItemCustomizerMigrationTest {
	/** A layout file with {@code modules} (the "modules" object's members), read as Flint reads it. */
	private static Layout read(String modules) {
		JsonObject root = JsonParser.parseString("{\"schema\":1,\"name\":\"Test\",\"modules\":{" + modules + "}}").getAsJsonObject();
		return LayoutCodec.read(root, "test");
	}

	private static ModuleState view(Layout layout) {
		return layout.module(ViewModel.ID);
	}

	@Test
	void viewModelGroupsThatWereOffGoBackToMinecraftsPlace() {
		Layout layout = read("\"view_model\":{\"enabled\":true,\"settings\":{\"position\":false,\"positionX\":0.5,\"positionY\":-0.3,"
			+ "\"rotation\":true,\"rotationY\":45,\"scale\":false,\"scaleX\":2,\"scaleY\":0.5,\"shieldHeight\":0}}");
		ModuleState v = view(layout);
		assertTrue(v.enabled());
		assertEquals(0F, v.decimal(ViewModel.POSITION_X), "a group that was off moved nothing");
		assertEquals(0F, v.decimal(ViewModel.POSITION_Y));
		assertEquals(45F, v.decimal(ViewModel.ROTATION_Y), "a group that was on keeps its numbers");
		assertEquals(1F, v.decimal(ViewModel.SCALE_X));
		assertEquals(1F, v.decimal(ViewModel.SCALE_Y));
		assertTrue(v.bool(ViewModel.FIRST_PERSON));
		assertTrue(v.bool(ViewModel.ITEMS));
		assertTrue(v.get(ViewModel.HELD).isEmpty(), "no shield height, no Shields entry");
	}

	@Test
	void aShieldHeightBecomesAShieldsEntry() {
		Layout layout = read("\"view_model\":{\"enabled\":true,\"settings\":{\"position\":true,\"positionX\":0.2,\"positionY\":0.1,"
			+ "\"scale\":true,\"scaleX\":1.5,\"scaleY\":1.5,\"scaleZ\":1.5,\"shieldHeight\":-0.4}}");
		Map<String, HeldTransform> held = view(layout).get(ViewModel.HELD);
		HeldTransform shields = held.get("category:shields");
		assertEquals(1, held.size());
		assertEquals(0.2F, shields.x(), 1e-6);
		assertEquals(-0.3F, shields.y(), 1e-6, "every item's height, lowered by the shield's");
		assertEquals(1.5F, shields.scaleX(), 1e-6);
	}

	@Test
	void aNewFileIsLeftAlone() {
		Layout layout = read("\"view_model\":{\"enabled\":true,\"settings\":{\"positionX\":0.5,\"firstPerson\":true}}");
		assertEquals(0.5F, view(layout).decimal(ViewModel.POSITION_X), "no group switches in the file: Item Customizer's own numbers");
	}

	@Test
	void swingDurationBecomesSwingSpeedWhereAnimationsWasOn() {
		Layout layout = read("\"animations\":{\"enabled\":true,\"settings\":{\"modifySwingDuration\":true,\"swingDurationMultiplier\":2.0}},"
			+ "\"view_model\":{\"enabled\":false,\"settings\":{\"position\":true,\"positionX\":0.7}}");
		ModuleState v = view(layout);
		assertTrue(v.enabled(), "Item Customizer takes the swing over, so it is switched on");
		assertTrue(v.bool(ViewModel.SWING_SPEED));
		assertEquals(0.5F, v.decimal(ViewModel.SWING_SPEED_VALUE), 1e-6, "twice as long is half as fast");
		assertTrue(v.bool(ViewModel.ANIMATIONS));
		assertFalse(v.bool(ViewModel.FIRST_PERSON), "its own numbers were off with it: they stay off");
		assertFalse(v.bool(ViewModel.DROPPED));
	}

	@Test
	void aSwingDurationThatDidNothingIsntSwitchedOn() {
		Layout off = read("\"animations\":{\"enabled\":false,\"settings\":{\"modifySwingDuration\":true,\"swingDurationMultiplier\":0.5}}");
		assertFalse(view(off).enabled(), "Animations was off: the swing was Minecraft's");
		assertFalse(view(off).bool(ViewModel.SWING_SPEED));
		assertEquals(2F, view(off).decimal(ViewModel.SWING_SPEED_VALUE), 1e-6, "the number still comes over");
		Layout unused = read("\"animations\":{\"enabled\":true,\"settings\":{\"modifySwingDuration\":false,\"swingDurationMultiplier\":3}}");
		assertFalse(view(unused).enabled());
	}

	@Test
	void itemPhysicsScalesBecomeDroppedItemSize() {
		Layout layout = read("\"item_physics\":{\"enabled\":true,\"settings\":{\"itemScale\":2.0,\"uhcOverlay\":1.5,"
			+ "\"uhcItemList\":[\"minecraft:golden_apple\",\"minecraft:diamond\"]}}");
		ModuleState v = view(layout);
		assertTrue(v.enabled());
		assertTrue(v.bool(ViewModel.DROPPED));
		assertFalse(v.bool(ViewModel.FIRST_PERSON));
		assertFalse(v.bool(ViewModel.ANIMATIONS));
		assertEquals(2F, v.decimal(ViewModel.DROPPED_SCALE), 1e-6);
		assertEquals(1.5F, v.decimal(ViewModel.DROPPED_SELECTED_SCALE), 1e-6);
		assertEquals(List.of("minecraft:diamond", "minecraft:golden_apple"), v.ids(ViewModel.DROPPED_ITEMS));
	}

	@Test
	void itemPhysicsOffOrAtItsDefaultsChangesNothing() {
		assertFalse(view(read("\"item_physics\":{\"enabled\":false,\"settings\":{\"itemScale\":2.0}}")).enabled());
		Layout defaults = read("\"item_physics\":{\"enabled\":true,\"settings\":{\"itemScale\":1.0,\"uhcOverlay\":0,"
			+ "\"uhcItemList\":[\"minecraft:apple\"]}}");
		assertFalse(view(defaults).enabled());
		assertTrue(view(defaults).ids(ViewModel.DROPPED_ITEMS).isEmpty(), "a custom scale of 0 was off: its list stays behind");
	}

	@Test
	void anItemCustomizerThatWasOnKeepsItsSectionsAndGainsTheMovedOnes() {
		Layout layout = read("\"view_model\":{\"enabled\":true,\"settings\":{\"firstPerson\":true,\"droppedSize\":false}},"
			+ "\"item_physics\":{\"enabled\":true,\"settings\":{\"itemScale\":0.5}}");
		ModuleState v = view(layout);
		assertTrue(v.bool(ViewModel.FIRST_PERSON));
		assertTrue(v.bool(ViewModel.DROPPED), "the moved size is turned on where it lands");
		assertEquals(0.5F, v.decimal(ViewModel.DROPPED_SCALE), 1e-6);
	}

	@Test
	void valuesItemCustomizerAlreadyHasWin() {
		Layout layout = read("\"view_model\":{\"enabled\":true,\"settings\":{\"swingSpeed\":false,\"droppedScale\":1.0}},"
			+ "\"animations\":{\"enabled\":true,\"settings\":{\"modifySwingDuration\":true,\"swingDurationMultiplier\":2}},"
			+ "\"item_physics\":{\"enabled\":true,\"settings\":{\"itemScale\":3}}");
		ModuleState v = view(layout);
		assertFalse(v.bool(ViewModel.SWING_SPEED));
		assertEquals(1F, v.decimal(ViewModel.DROPPED_SCALE), 1e-6);
	}

	@Test
	void theOlderKeysLeaveTheFileWhenItIsSaved() {
		JsonObject root = JsonParser.parseString("{\"schema\":1,\"name\":\"Test\",\"modules\":{"
			+ "\"view_model\":{\"enabled\":true,\"settings\":{\"position\":true,\"shieldHeight\":0.2,\"scale\":false,\"rotation\":false}},"
			+ "\"animations\":{\"enabled\":true,\"settings\":{\"modifySwingDuration\":true,\"swingDurationMultiplier\":1.5}},"
			+ "\"item_physics\":{\"enabled\":true,\"settings\":{\"itemScale\":1.2,\"uhcOverlay\":0,\"uhcItemList\":[]}},"
			+ "\"crosshair\":{\"enabled\":true,\"settings\":{\"drawCrosshair\":\"closed\"}}}}").getAsJsonObject();
		Layout layout = LayoutCodec.read(root, "test");
		LayoutCodec.write(layout, root);
		JsonObject modules = root.getAsJsonObject("modules");
		JsonObject view = modules.getAsJsonObject("view_model").getAsJsonObject("settings");
		for (String gone : List.of("position", "shieldHeight", "scale", "rotation")) {
			assertFalse(view.has(gone), gone);
		}
		assertTrue(view.has("heldItems"));
		assertFalse(modules.getAsJsonObject("animations").getAsJsonObject("settings").has("modifySwingDuration"));
		assertFalse(modules.getAsJsonObject("item_physics").getAsJsonObject("settings").has("itemScale"));
		assertFalse(modules.getAsJsonObject("crosshair").getAsJsonObject("settings").has("drawCrosshair"));
		// Read again, the saved file gives the same Item Customizer (and runs no migration twice).
		Layout again = LayoutCodec.read(root, "test");
		assertEquals(view(layout).get(ViewModel.HELD), view(again).get(ViewModel.HELD));
		assertEquals(view(layout).decimal(ViewModel.SWING_SPEED_VALUE), view(again).decimal(ViewModel.SWING_SPEED_VALUE));
		assertEquals(view(layout).decimal(ViewModel.DROPPED_SCALE), view(again).decimal(ViewModel.DROPPED_SCALE));
		assertTrue(view(again).bool(ViewModel.SWING_SPEED));
	}

	@Test
	void theRetiredKeysAreListed() {
		assertTrue(ModuleMigrations.retiredKeys(ModuleRegistry.get(ViewModel.ID)).contains("shieldHeight"));
		assertTrue(ModuleMigrations.retiredKeys(ModuleRegistry.get(ToolMods.Animations.ID)).contains("swingDurationMultiplier"));
		assertTrue(ModuleMigrations.retiredKeys(ModuleRegistry.get(VisualMods.ItemPhysics.ID)).contains("uhcItemList"));
		assertNull(ModuleRegistry.get(ViewModel.ID).setting("shieldHeight"));
	}
}

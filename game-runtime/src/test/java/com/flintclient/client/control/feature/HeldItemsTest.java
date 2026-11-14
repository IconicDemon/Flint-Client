package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.HeldItemsSetting;
import com.flintclient.client.control.model.HeldTransform;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Item Customizer's rules: which place an item takes, the per-item map behind it, the swing's ticks and dropped sizes. */
class HeldItemsTest {
	private static final HeldTransform UP = new HeldTransform(0, 0.5F, 0, 0, 0, 0, 1, 1, 1);
	private static final HeldTransform BIG = new HeldTransform(0, 0, 0, 0, 0, 0, 2, 2, 2);
	private static final HeldTransform TURNED = new HeldTransform(0, 0, 0, 0, 90, 0, 1, 1, 1);

	private static ModuleState state() {
		return new ModuleState(ModuleRegistry.get(ViewModel.ID));
	}

	private static void add(ModuleState s, String id, HeldTransform t) {
		s.set(ViewModel.HELD, ViewModel.HELD.with(s.get(ViewModel.HELD), id, t));
	}

	@Test
	void nothingChangedLeavesMinecraftsPlace() {
		assertNull(HeldItems.resolve(state(), "minecraft:diamond_sword", HeldItems.Category.SWORDS.bit()));
	}

	@Test
	void anItemsOwnEntryWinsThenItsFirstCategoryThenEveryItem() {
		ModuleState s = state();
		s.set(ViewModel.POSITION_Y, 0.25F);
		add(s, HeldItems.Category.AXES.id(), BIG);
		add(s, HeldItems.Category.SWORDS.id(), TURNED);
		add(s, "minecraft:golden_sword", UP);
		int swordAndAxe = HeldItems.Category.SWORDS.bit() | HeldItems.Category.AXES.bit();
		assertEquals(UP, HeldItems.resolve(s, "minecraft:golden_sword", HeldItems.Category.SWORDS.bit()), "its own entry first");
		assertEquals(BIG, HeldItems.resolve(s, "minecraft:odd_tool", swordAndAxe), "then the first of its categories in the list");
		assertEquals(TURNED, HeldItems.resolve(s, "minecraft:iron_sword", HeldItems.Category.SWORDS.bit()));
		HeldTransform every = HeldItems.resolve(s, "minecraft:stick", 0);
		assertEquals(0.25F, every.y(), "else every item's place");
		s.set(ViewModel.ITEMS, false);
		assertNull(HeldItems.resolve(s, "minecraft:stick", 0), "Items off: Minecraft's own place");
		assertEquals(UP, HeldItems.resolve(s, "minecraft:golden_sword", 0), "entries don't need Items");
		s.set(ViewModel.FIRST_PERSON, false);
		assertNull(HeldItems.resolve(s, "minecraft:golden_sword", 0), "the section off turns them all off");
	}

	@Test
	void theLookupKeepsAnswersPerItemUntilASettingChanges() {
		Map<Object, Integer> asked = new HashMap<>();
		Object sword = new Object();
		Object stick = new Object();
		HeldItems.Lookup lookup = new HeldItems.Lookup(new HeldItems.Facts() {
			@Override
			public String id(Object item) {
				asked.merge(item, 1, Integer::sum);
				return item == sword ? "minecraft:iron_sword" : "minecraft:stick";
			}

			@Override
			public int categories(Object item) {
				return item == sword ? HeldItems.Category.SWORDS.bit() : 0;
			}
		});
		ModuleState s = state();
		add(s, HeldItems.Category.SWORDS.id(), TURNED);
		HeldTransform first = lookup.place(s, sword);
		assertEquals(TURNED, first);
		assertNull(lookup.place(s, stick));
		for (int i = 0; i < 100; i++) {
			assertSame(first, lookup.place(s, sword));
			assertNull(lookup.place(s, stick));
		}
		assertEquals(1, asked.get(sword), "worked out once, then read from the map");
		assertEquals(1, asked.get(stick));
		add(s, "minecraft:iron_sword", UP);
		assertEquals(UP, lookup.place(s, sword), "a setting changed: worked out again");
		ModuleState other = state();
		assertNull(lookup.place(other, sword), "another profile's state: its own answers");
	}

	@Test
	void theEmptyHandHasItsOwnPlace() {
		HeldItems.Lookup lookup = new HeldItems.Lookup(new HeldItems.Facts() {
			@Override
			public String id(Object item) {
				return "minecraft:stick";
			}

			@Override
			public int categories(Object item) {
				return 0;
			}
		});
		ModuleState s = state();
		assertNull(lookup.hand(s));
		HeldItems.write(s, ViewModel.HAND_TRANSFORM, BIG);
		assertEquals(BIG, lookup.hand(s));
		s.set(ViewModel.EMPTY_HANDS, false);
		assertNull(lookup.hand(s));
		assertEquals(BIG, HeldItems.read(s, ViewModel.HAND_TRANSFORM));
	}

	@Test
	void swingTicksIgnoreHasteOrFatigueThenTakeTheSpeed() {
		// Minecraft's 6 ticks: Haste II takes 3 off, Mining Fatigue I adds 2.
		assertEquals(3, HeldItems.swingTicks(3, 3, 0, false, false, 1));
		assertEquals(6, HeldItems.swingTicks(3, 3, 0, true, false, 1), "Haste ignored: Minecraft's own 6");
		assertEquals(8, HeldItems.swingTicks(8, 0, 1, false, false, 1));
		assertEquals(6, HeldItems.swingTicks(8, 0, 1, false, true, 1), "Mining Fatigue ignored");
		assertEquals(8, HeldItems.swingTicks(3, 3, 1, true, false, 1), "with Haste ignored, Mining Fatigue counts as Minecraft would");
		assertEquals(3, HeldItems.swingTicks(6, 0, 0, false, false, 2), "twice as fast");
		assertEquals(12, HeldItems.swingTicks(6, 0, 0, false, false, 0.5F));
		assertEquals(1, HeldItems.swingTicks(1, 0, 0, false, false, 4), "never under a tick");
		ModuleState s = state();
		assertEquals(6, HeldItems.swingTicks(s, 6, 0, 0), "nothing chosen: Minecraft's");
		s.set(ViewModel.SWING_SPEED, true);
		s.set(ViewModel.SWING_SPEED_VALUE, 2F);
		assertEquals(3, HeldItems.swingTicks(s, 6, 0, 0));
		s.set(ViewModel.ANIMATIONS, false);
		assertEquals(6, HeldItems.swingTicks(s, 6, 0, 0), "the section off");
	}

	@Test
	void droppedItemsTakeTheSelectedSizeOrEveryItems() {
		ModuleState s = state();
		assertEquals(1F, HeldItems.droppedScale(s, "minecraft:apple"));
		s.set(ViewModel.DROPPED_SCALE, 1.5F);
		s.set(ViewModel.DROPPED_SELECTED_SCALE, 3F);
		s.set(ViewModel.DROPPED_ITEMS, List.of("minecraft:beacon", "minecraft:golden_apple"));
		assertEquals(3F, HeldItems.droppedScale(s, "minecraft:golden_apple"));
		assertEquals(1.5F, HeldItems.droppedScale(s, "minecraft:apple"));
		s.set(ViewModel.DROPPED, false);
		assertEquals(1F, HeldItems.droppedScale(s, "minecraft:golden_apple"));
	}

	@Test
	void theListKeepsItsOrderAndClampsWhatItReads() {
		HeldItemsSetting held = ViewModel.HELD;
		Map<String, HeldTransform> read = held.parse(JsonParser.parseString("{\"category:swords\":[0,0,0,0,0,0,1,1,1],"
			+ "\"minecraft:bow\":[9,0,0,400,0,0,-1,1,1],\"Not An Id\":[0,0,0,0,0,0,1,1,1],\"minecraft:stick\":[1,2],"
			+ "\"category:axes\":[0.123,0,0,0,0,0,1,1,1]}"));
		assertEquals(List.of("category:swords", "minecraft:bow", "category:axes"), List.copyOf(read.keySet()));
		HeldTransform bow = read.get("minecraft:bow");
		assertEquals(2F, bow.x(), "position at most 2");
		assertEquals(180F, bow.rotX(), "rotation at most 180°");
		assertEquals(0F, bow.scaleX(), "scale at least 0");
		assertEquals(0.12F, read.get("category:axes").x(), 1e-6, "on the hundredth");
		assertTrue(held.valid(read));
		assertEquals(read, held.parse(held.toJson(read)), "written and read back the same");
		Map<String, HeldTransform> without = held.without(read, "minecraft:bow");
		assertEquals(List.of("category:swords", "category:axes"), List.copyOf(without.keySet()));
	}

	@Test
	void everyCategoryHasAnIdThePickerCanName() {
		for (HeldItems.Category c : HeldItems.Category.values()) {
			assertSame(c, HeldItems.Category.of(c.id()));
			assertTrue(HeldItemsSetting.validKey(c.id()), c.id());
			assertTrue(c.picture().startsWith("minecraft:"));
		}
		assertNull(HeldItems.Category.of("minecraft:diamond_sword"));
	}
}

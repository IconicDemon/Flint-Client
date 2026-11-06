package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryRulesTest {
	@Test
	void aBindingJoinsOneInventorySlotToOneHotbarSlot() {
		List<String> bound = InventoryRules.bind(List.of(), 12, 3);
		assertEquals(List.of("bind:12-3"), bound);
		assertEquals(3, InventoryRules.boundTo(bound, 12));
		assertEquals(12, InventoryRules.boundTo(bound, 3));
		assertEquals(-1, InventoryRules.boundTo(bound, 4));
		assertEquals(List.of("bind:20-3"), InventoryRules.bind(bound, 20, 3), "the hotbar slot moves to its new partner");
		assertEquals(List.of("bind:12-5"), InventoryRules.bind(bound, 12, 5), "so does the inventory slot");
		assertEquals(List.of("bind:12-3", "bind:30-0"), InventoryRules.bind(bound, 30, 0));
		assertEquals(List.of(), InventoryRules.unbind(bound, 3));
		assertEquals(List.of(), InventoryRules.unbind(bound, 12));
		assertEquals(bound, InventoryRules.unbind(bound, 4));
	}

	@Test
	void onlyRealBindingsCount() {
		assertArrayEquals(new int[] {9, 8}, InventoryRules.binding("bind:9-8"));
		assertNull(InventoryRules.binding("bind:8-8"), "a hotbar slot isn't an inventory slot");
		assertNull(InventoryRules.binding("bind:36-0"), "armor can't be bound");
		assertNull(InventoryRules.binding("bind:12"));
		assertNull(InventoryRules.binding("slot:12"));
		assertEquals(-1, InventoryRules.boundTo(List.of("bind:x-1", "slot:3"), 3));
	}

	@Test
	void enchantmentsAreSummedUpByTheirFirstLetters() {
		assertEquals("Sh5", InventoryRules.enchantCode("Sharpness", 5, 5));
		assertEquals("Un3", InventoryRules.enchantCode("Unbreaking", 3, 3));
		assertEquals("Me", InventoryRules.enchantCode("Mending", 1, 1), "no level when there's only one");
		assertEquals("FA2", InventoryRules.enchantCode("Fire Aspect", 2, 2));
		assertEquals("CV", InventoryRules.enchantCode("Curse of Vanishing", 1, 1));
		assertEquals("BA4", InventoryRules.enchantCode("Bane of Arthropods", 4, 5));
		assertEquals("LS3", InventoryRules.enchantCode("Luck of the Sea", 3, 3));
		assertEquals("Ef10", InventoryRules.enchantCode("Efficiency", 10, 5), "a level past the usual most is shown as it is");
		assertEquals("?", InventoryRules.enchantCode("  ", 1, 1));
	}

	@Test
	void easingsStartAtZeroAndEndAtOne() {
		for (int easing = 0; easing < 4; easing++) {
			assertEquals(0, InventoryRules.ease(easing, 0), 1e-6);
			assertEquals(1, InventoryRules.ease(easing, 1), 1e-6);
			assertEquals(1, InventoryRules.ease(easing, 2), 1e-6, "past the end stays at the end");
		}
		assertTrue(InventoryRules.ease(0, 0.5F) > 0.9F, "out quint is nearly there halfway");
		assertEquals(0.5F, InventoryRules.ease(3, 0.5F), 1e-6);
		assertTrue(InventoryRules.ease(2, 0.8F) > 1, "out back overshoots before it settles");
	}
}

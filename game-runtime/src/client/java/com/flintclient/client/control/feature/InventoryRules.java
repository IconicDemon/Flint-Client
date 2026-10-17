package com.flintclient.client.control.feature;

import java.util.List;
import java.util.Locale;

/**
 * Inventory's decisions, version-neutral: whether an item matches the search, the ids of locked slots, and whether an
 * action on a slot is allowed. Pure Java.
 */
public final class InventoryRules {
	private InventoryRules() {
	}

	/** The words of a search (lower case, split on spaces); none for a blank search. */
	public static String[] words(String query) {
		String q = query.toLowerCase(Locale.ROOT).strip();
		return q.isEmpty() ? new String[0] : q.split("\\s+");
	}

	/** Whether every word is in {@code text} (already lower case). An empty search matches everything. */
	public static boolean matches(String[] words, String text) {
		for (String word : words) {
			if (!text.contains(word)) {
				return false;
			}
		}
		return true;
	}

	/** The id a locked slot is stored as. */
	public static String slotId(int inventoryIndex) {
		return "slot:" + inventoryIndex;
	}

	public static boolean locked(List<String> lockedIds, int inventoryIndex) {
		return inventoryIndex >= 0 && lockedIds.contains(slotId(inventoryIndex));
	}

	/** Whether {@code itemId} is protected from dropping. */
	public static boolean protectedItem(List<String> protectedIds, String itemId) {
		return protectedIds.contains(itemId);
	}

	/** The short label of a hotbar key ("1", "Q", "Mouse 4") as shown on a slot. */
	public static String keyLabel(String name, boolean lowercase) {
		String label = name.length() > 3 && !name.startsWith("Mouse") ? name.substring(0, 3) : name;
		return lowercase ? label.toLowerCase(Locale.ROOT) : label;
	}
}

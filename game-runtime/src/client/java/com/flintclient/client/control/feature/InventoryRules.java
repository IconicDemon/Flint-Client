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

	// ---------- slot binding ----------

	/** The id a binding of inventory slot {@code inventoryIndex} (9-35) to hotbar slot {@code hotbarIndex} (0-8) is stored as. */
	public static String bindingId(int inventoryIndex, int hotbarIndex) {
		return "bind:" + inventoryIndex + "-" + hotbarIndex;
	}

	/** The other slot of the binding {@code slot} (hotbar or inventory index) is in, or -1 when it has none. */
	public static int boundTo(List<String> bindings, int slot) {
		for (String id : bindings) {
			int[] pair = binding(id);
			if (pair != null && pair[0] == slot) {
				return pair[1];
			}
			if (pair != null && pair[1] == slot) {
				return pair[0];
			}
		}
		return -1;
	}

	/** {@code bindings} with inventory slot {@code inventoryIndex} bound to hotbar slot {@code hotbarIndex}, each in no other binding. */
	public static List<String> bind(List<String> bindings, int inventoryIndex, int hotbarIndex) {
		java.util.TreeSet<String> out = new java.util.TreeSet<>(unbind(bindings, inventoryIndex));
		out.removeAll(bindingsOf(out, hotbarIndex));
		out.add(bindingId(inventoryIndex, hotbarIndex));
		return List.copyOf(out);
	}

	/** {@code bindings} without any binding {@code slot} (hotbar or inventory index) is in. */
	public static List<String> unbind(List<String> bindings, int slot) {
		java.util.TreeSet<String> out = new java.util.TreeSet<>(bindings);
		out.removeAll(bindingsOf(out, slot));
		return List.copyOf(out);
	}

	private static List<String> bindingsOf(java.util.Collection<String> bindings, int slot) {
		List<String> out = new java.util.ArrayList<>();
		for (String id : bindings) {
			int[] pair = binding(id);
			if (pair != null && (pair[0] == slot || pair[1] == slot)) {
				out.add(id);
			}
		}
		return out;
	}

	/** A stored binding as {inventory index, hotbar index}, or null when it isn't one. */
	public static int @org.jspecify.annotations.Nullable [] binding(String id) {
		if (!id.startsWith("bind:")) {
			return null;
		}
		int dash = id.indexOf('-', 5);
		try {
			int inventory = Integer.parseInt(id.substring(5, dash));
			int hotbar = Integer.parseInt(id.substring(dash + 1));
			return inventory >= 9 && inventory <= 35 && hotbar >= 0 && hotbar <= 8 ? new int[] {inventory, hotbar} : null;
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	// ---------- enchantment summaries ----------

	private static final java.util.Set<String> SMALL_WORDS = java.util.Set.of("of", "the", "de", "du", "des", "la", "le", "der", "die", "das",
		"von");

	/**
	 * An enchantment's summary on a slot: the first letters of its name ("Sharpness" Sh, "Fire Aspect" FA, "Curse of
	 * Vanishing" CV) and its level when it has more than one ("Sh5").
	 */
	public static String enchantCode(String name, int level, int maxLevel) {
		List<String> words = new java.util.ArrayList<>();
		for (String word : name.strip().split("\\s+")) {
			if (!word.isEmpty() && !SMALL_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
				words.add(word);
			}
		}
		String code;
		if (words.isEmpty()) {
			code = "?";
		} else if (words.size() == 1) {
			String word = words.get(0);
			int second = word.offsetByCodePoints(0, Math.min(2, word.codePointCount(0, word.length())));
			code = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1, second).toLowerCase(Locale.ROOT);
		} else {
			code = (words.get(0).substring(0, 1) + words.get(1).substring(0, 1)).toUpperCase(Locale.ROOT);
		}
		return maxLevel > 1 ? code + level : code;
	}

	// ---------- open / close animations ----------

	/** How far along an animation {@code t} (0..1) of the way through is, eased: 0 out quint, 1 out cubic, 2 out back, 3 linear. */
	public static float ease(int easing, float t) {
		float x = Math.max(0, Math.min(1, t));
		float inv = 1 - x;
		return switch (easing) {
			case 0 -> 1 - inv * inv * inv * inv * inv;
			case 1 -> 1 - inv * inv * inv;
			case 2 -> {
				float c1 = 1.70158F;
				float c3 = c1 + 1;
				yield 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1);
			}
			default -> x;
		};
	}
}

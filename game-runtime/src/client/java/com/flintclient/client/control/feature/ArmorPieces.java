package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.ModuleSettings.Armor;
import com.flintclient.client.control.model.ModuleState;

/**
 * What Armor's strip shows after the armour, and its durability text, free of Minecraft so every line (1.8.9 included) picks
 * and words them the same way. Each line sorts the items in its hotbar and offhand into {@linkplain #SWORD kinds}; this picks
 * the one each slot shows: a sword, pickaxe, axe, mace, trident or bow is the one held when it is of that kind, else the first
 * of that kind in the hotbar; a shield is the one in the offhand first. An item shows in one slot at most.
 */
public final class ArmorPieces {
	/** The item slots' kinds, in the order their slots follow the armour ({@link Armor#ITEMS}). */
	public static final int SWORD = 0;
	public static final int PICKAXE = 1;
	public static final int AXE = 2;
	public static final int MACE = 3;
	public static final int TRIDENT = 4;
	public static final int BOW = 5;
	public static final int SHIELD = 6;
	public static final int KINDS = 7;
	/** Every kind, as bits ({@code 1 << kind}). */
	public static final int ALL = (1 << KINDS) - 1;
	/** What Minecraft 1.8.9 has: no shields, tridents or maces (and no offhand to hold a shield in). */
	public static final int ALL_1_8_9 = ALL & ~(1 << SHIELD | 1 << TRIDENT | 1 << MACE);
	/** The places looked in: the hotbar's slots 0 to 8, then the offhand. */
	public static final int HOTBAR = 9;
	public static final int OFFHAND = 9;
	public static final int PLACES = 10;
	/** No item for a slot. */
	public static final int NONE = -1;
	/** The most slots the strip can have: the four armour pieces and every kind. */
	public static final int MAX_SLOTS = 4 + KINDS;
	/** The texts kept ({@link Texts}): one per slot of the strip, then the offhand's and the main hand's over the hotbar. */
	public static final int OFFHAND_TEXT = MAX_SLOTS;
	public static final int MAIN_HAND_TEXT = MAX_SLOTS + 1;
	public static final int TEXTS = MAX_SLOTS + 2;

	/** Durability display (Armor's {@code durabilityDisplay}): the bar, the uses left, or the percentage left. */
	public static final int BAR = 0;
	public static final int NUMERIC = 1;
	public static final int PERCENTAGE = 2;

	private static final String BOLD = "§l";
	/** The widest text of each length, to keep the strip's room for text steady as the numbers change: "0" to "00000". */
	private static final String[] ZEROS = {"0", "00", "000", "0000", "00000"};
	private static final String[] BOLD_ZEROS = {BOLD + "0", BOLD + "00", BOLD + "000", BOLD + "0000", BOLD + "00000"};
	private static final String PERCENT = "00%";
	private static final String BOLD_PERCENT = BOLD + PERCENT;

	private ArmorPieces() {
	}

	/** The kinds whose slots are turned on in {@code armor}'s settings, as bits, less those this Minecraft doesn't have ({@code available}). */
	public static int wanted(ModuleState armor, int available) {
		int wanted = 0;
		for (int kind = 0; kind < KINDS; kind++) {
			if (armor.bool(Armor.ITEMS.get(kind))) {
				wanted |= 1 << kind;
			}
		}
		return wanted & available;
	}

	/**
	 * Which place each wanted kind's slot shows, into {@code out[kind]} ({@link #NONE} when there is none, and for kinds not
	 * wanted). Kinds take their place in order and a place is taken once, so an item never shows in two slots.
	 *
	 * @param kinds each place's kinds as bits ({@code 1 << SWORD}...): the hotbar's slots 0 to 8, then the {@link #OFFHAND}
	 * @param selected the hotbar slot held in the main hand (0 to 8)
	 * @param wanted the kinds whose slots show, as bits
	 * @param out one entry per kind ({@link #KINDS})
	 */
	public static void pick(int[] kinds, int selected, int wanted, int[] out) {
		int used = 0;
		for (int kind = 0; kind < KINDS; kind++) {
			out[kind] = NONE;
			if ((wanted & 1 << kind) == 0) {
				continue;
			}
			int bit = 1 << kind;
			int found = NONE;
			// A shield is at home in the offhand; everything else is the held one first, then the hotbar from the left.
			if (kind == SHIELD && free(kinds, OFFHAND, bit, used)) {
				found = OFFHAND;
			} else if (selected >= 0 && selected < HOTBAR && free(kinds, selected, bit, used)) {
				found = selected;
			} else {
				for (int slot = 0; slot < HOTBAR; slot++) {
					if (free(kinds, slot, bit, used)) {
						found = slot;
						break;
					}
				}
			}
			if (found != NONE) {
				out[kind] = found;
				used |= 1 << found;
			}
		}
	}

	private static boolean free(int[] kinds, int place, int bit, int used) {
		return place < kinds.length && (kinds[place] & bit) != 0 && (used & 1 << place) == 0;
	}

	// ---------- durability ----------

	/** Whether an item is about to break: at or below the minimum percentage left, or the minimum uses left. */
	public static boolean endangered(int damage, int maxDamage, double minPercentage, int minValue) {
		if (maxDamage <= 0) {
			return false;
		}
		double percentage = 1.0 - (double) damage / maxDamage;
		return percentage <= minPercentage || maxDamage - damage <= minValue;
	}

	/** The durability as text: the uses left, or the percentage left in whole percent (nothing while undamaged). */
	public static String durabilityText(int display, int damage, int maxDamage) {
		if (display == NUMERIC) {
			return String.valueOf(maxDamage - damage);
		}
		if (damage == 0 || maxDamage <= 0) {
			return "";
		}
		double percentage = 1.0 - (double) damage / maxDamage;
		return (int) Math.floor(percentage * 100.0) + "%";
	}

	/**
	 * The widest text an item with {@code maxDamage} uses can show in {@code display} ("000" for 250 uses, "00%"), bold with
	 * Minecraft's formatting code when {@code bold}: what the strip keeps room for, so it doesn't twitch as the numbers fall.
	 */
	public static String widest(int display, int maxDamage, boolean bold) {
		if (display == PERCENTAGE) {
			return bold ? BOLD_PERCENT : PERCENT;
		}
		int digits = Math.clamp(String.valueOf(Math.max(0, maxDamage)).length(), 1, ZEROS.length);
		return bold ? BOLD_ZEROS[digits - 1] : ZEROS[digits - 1];
	}

	/**
	 * Each slot's durability text, made again only when what it shows changes (the HUD draws every frame): the uses or percentage
	 * left, in bold (Minecraft's formatting code) when asked. One per surface; render thread.
	 */
	public static final class Texts {
		private final String[] texts = new String[TEXTS];
		private final long[] keys = new long[TEXTS];

		public Texts() {
			java.util.Arrays.fill(keys, Long.MIN_VALUE);
		}

		/** Slot {@code slot}'s text for an item with {@code damage} of {@code maxDamage} used, in {@code display}. */
		public String get(int slot, int display, int damage, int maxDamage, boolean bold) {
			long key = (long) damage << 34 | (long) (maxDamage & 0x7FFFFFFF) << 3 | (long) display << 1 | (bold ? 1 : 0);
			String text = texts[slot];
			if (text == null || keys[slot] != key) {
				String plain = durabilityText(display, damage, maxDamage);
				text = bold && !plain.isEmpty() ? BOLD + plain : plain;
				texts[slot] = text;
				keys[slot] = key;
			}
			return text;
		}
	}
}

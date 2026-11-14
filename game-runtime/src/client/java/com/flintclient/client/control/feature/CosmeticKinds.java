package com.flintclient.client.control.feature;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Flint Store's cosmetic kinds, the slot each is worn in and the wardrobe category it shows under: the one place in the
 * game that names them (the Store's cosmetics v2 contract, FlintQA/release-104/cosmetics-contract-website.md; the launcher has
 * the same table in store/kinds.rs). A rename there is a one-line change here.
 *
 * <p>A kind the game doesn't know yet (a newer Store) is kept as it came: the wardrobe shows it under "More" and doesn't wear
 * it until a game that knows its slot. The Store's older names {@code back} and {@code head} read as {@code wings} and
 * {@code headwear}. Pure Java, every line.
 */
public final class CosmeticKinds {
	private CosmeticKinds() {
	}

	/** One kind: the Store's name, the slot it is worn in (null: an emote, on the wheel instead), its category. */
	public record Kind(String name, @Nullable String slot, Category category) {
	}

	/**
	 * The wardrobe's categories. The Store's eight kinds come first, in the sidebar's order (Capes, Wings, Hats, Auras, Pets,
	 * Shields, Gloves, Emotes: {@link #main}); "Other" (kinds a newer Store has) holds Store items too but has no place in the
	 * sidebar; Bodywear is coming (listed, "Coming soon"); Outfits, Skin Changer and Badges are the game's own pages, under the main ones.
	 */
	public enum Category {
		CAPES("Capes", "Cape"),
		WINGS("Wings", "Wings"),
		/** The Store's kind {@code headwear}, shown as Hats everywhere (the owner's word, as the Store's bar). */
		HEADWEAR("Hats", "Hat"),
		AURAS("Auras", "Aura"),
		PETS("Pets", "Pet"),
		SHIELDS("Shields", "Shield"),
		GLOVES("Gloves", "Gloves"),
		/** Played from the emote wheel (8 places), not worn in a slot. */
		EMOTES("Emotes", "Emote"),
		/** Not in the Store yet: the sidebar lists it after the main kinds with "Coming soon". */
		BODYWEAR("Bodywear", "Bodywear"),
		/** Kinds a newer Store has that this game doesn't know yet: kept, never shown. */
		MORE("Other", "Item"),
		OUTFITS("Outfits", "Outfit"),
		SKINS("Skin Changer", "Skin"),
		BADGES("Badges", "Badge");

		/** The Store's kinds, the sidebar's main group. */
		public boolean main() {
			return ordinal() <= EMOTES.ordinal();
		}

		public final String title;
		/** One item of it ("Cape", "Wings"), for "No cape yet", "Wear this cape". */
		public final String one;

		Category(String title, String one) {
			this.title = title;
			this.one = one;
		}

		/** Holds Store items (not Outfits, Skin Changer or Badges). */
		public boolean store() {
			return ordinal() <= MORE.ordinal();
		}
	}

	public static final List<Kind> KINDS = List.of(
		new Kind("cape", "cape", Category.CAPES),
		new Kind("wings", "back", Category.WINGS),
		new Kind("headwear", "head", Category.HEADWEAR),
		new Kind("aura", "aura", Category.AURAS),
		new Kind("pet", "pet", Category.PETS),
		new Kind("shield", "shield", Category.SHIELDS),
		new Kind("gloves", "hands", Category.GLOVES),
		new Kind("emote", null, Category.EMOTES),
		// Coming: no slot in the Store yet, so nothing of it is worn.
		new Kind("bodywear", null, Category.BODYWEAR));

	/** The slots the Store knows (one item worn in each), in the loadout's order. */
	public static final List<String> SLOTS = List.of("cape", "back", "head", "aura", "pet", "shield", "hands");

	/** The emote wheel's places. */
	public static final int EMOTE_SLOTS = 8;

	/**
	 * The kind as the game names it: an older name read as today's, anything else as it came when it is a plain token
	 * ({@code a-z}, {@code 0-9}, {@code _}, at most 32), else null (the item is left out).
	 */
	public static @Nullable String normalize(@Nullable String kind) {
		if (kind == null) {
			return null;
		}
		String k = kind.strip();
		if (k.equals("back")) {
			return "wings";
		}
		if (k.equals("head")) {
			return "headwear";
		}
		if (k.isEmpty() || k.length() > 32) {
			return null;
		}
		for (int i = 0; i < k.length(); i++) {
			char ch = k.charAt(i);
			if (!(ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_')) {
				return null;
			}
		}
		return k;
	}

	public static @Nullable Kind kind(String name) {
		for (Kind k : KINDS) {
			if (k.name.equals(name)) {
				return k;
			}
		}
		return null;
	}

	/** The slot a kind is worn in (an older name too); null for an emote or a kind the game doesn't know. */
	public static @Nullable String slotOf(String kind) {
		String n = normalize(kind);
		Kind k = n == null ? null : kind(n);
		return k == null ? null : k.slot;
	}

	/** The category a kind shows under (MORE for one the game doesn't know). */
	public static Category categoryOf(String kind) {
		String n = normalize(kind);
		Kind k = n == null ? null : kind(n);
		return k == null ? Category.MORE : k.category;
	}

	/** The kind worn in a slot; null for a slot the game doesn't know. */
	public static @Nullable Kind ofSlot(String slot) {
		for (Kind k : KINDS) {
			if (slot.equals(k.slot)) {
				return k;
			}
		}
		return null;
	}

	/** The Store's name for a category's kind ("wings"), or null for a page of the game's own. */
	public static @Nullable String kindOf(Category category) {
		for (Kind k : KINDS) {
			if (k.category == category) {
				return k.name;
			}
		}
		return null;
	}

	/** Whether Minecraft {@code version} gets the wardrobe: 26.3 and newer ("26.3", "26.3.1", "26.4", "27.1"); not 26.2 or 1.21.11. */
	public static boolean wardrobeOn(String version) {
		String[] parts = version.split("[.-]");
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
			return major > 26 && major < 100 || major == 26 && minor >= 3;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	/** "cape" → "Cape", for a kind this game doesn't know ("jetpack_v2" → "Jetpack v2"). */
	public static String label(String kind) {
		Kind k = kind(kind);
		if (k != null) {
			return k.category.one;
		}
		String words = kind.replace('_', ' ');
		return words.isEmpty() ? "Item" : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}
}

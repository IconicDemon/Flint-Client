package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.HeldItemsSetting;
import com.flintclient.client.control.model.HeldTransform;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.ViewModel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Item Customizer's rules, the same on every Minecraft line: which place a held item takes (its own entry, else the first of its
 * categories in the list, else every item's), the empty hand's place, a dropped item's size and how long your swing takes. The
 * version layer says what an item is ({@link Facts}); the answers are kept per item and worked out again only when a setting
 * changes, so a frame asks a map and allocates nothing. Pure (no Minecraft classes); render thread.
 */
public final class HeldItems {
	/** Kinds of item an entry can name ({@code category:<key>}), in the order the picker lists them. */
	public enum Category {
		SWORDS("swords", "Swords", "minecraft:diamond_sword"),
		AXES("axes", "Axes", "minecraft:diamond_axe"),
		PICKAXES("pickaxes", "Pickaxes", "minecraft:diamond_pickaxe"),
		SHOVELS("shovels", "Shovels", "minecraft:diamond_shovel"),
		HOES("hoes", "Hoes", "minecraft:diamond_hoe"),
		BOWS("bows", "Bows & Crossbows", "minecraft:bow"),
		SHIELDS("shields", "Shields", "minecraft:shield"),
		FOOD("food", "Food", "minecraft:golden_apple"),
		POTIONS("potions", "Potions", "minecraft:potion"),
		THROWABLES("throwables", "Throwables", "minecraft:ender_pearl"),
		BLOCKS("blocks", "Blocks", "minecraft:stone"),
		ARMOR("armor", "Armor", "minecraft:diamond_chestplate");

		private final String key;
		private final String label;
		private final String picture;

		Category(String key, String label, String picture) {
			this.key = key;
			this.label = label;
			this.picture = picture;
		}

		/** Its id in the list ({@code category:swords}). */
		public String id() {
			return HeldItemsSetting.CATEGORY + key;
		}

		public String label() {
			return label;
		}

		/** The item drawn for it (a line without that item, 1.8.9 and shields, doesn't offer the category). */
		public String picture() {
			return picture;
		}

		public int bit() {
			return 1 << ordinal();
		}

		/** The category with list id {@code id}, or null. */
		public static @Nullable Category of(String id) {
			for (Category c : values()) {
				if (c.id().equals(id)) {
					return c;
				}
			}
			return null;
		}
	}

	/** Ids of the throwable items (the same on every line where they exist). */
	public static final List<String> THROWABLES = List.of("minecraft:snowball", "minecraft:egg", "minecraft:blue_egg", "minecraft:brown_egg",
		"minecraft:ender_pearl", "minecraft:ender_eye", "minecraft:experience_bottle", "minecraft:splash_potion", "minecraft:lingering_potion",
		"minecraft:wind_charge", "minecraft:fire_charge");

	/** What the version layer knows of an item: its id and the categories it is in ({@link Category#bit} together). */
	public interface Facts {
		String id(Object item);

		int categories(Object item);
	}

	private HeldItems() {
	}

	/**
	 * The place an item with id {@code id} in {@code categories} takes, or null for Minecraft's own (First Person Items off, or
	 * nothing applies or moves it): its own entry, else the first entry naming one of its categories, else every item's when
	 * Items is on.
	 */
	public static @Nullable HeldTransform resolve(ModuleState s, String id, int categories) {
		if (!s.bool(ViewModel.FIRST_PERSON)) {
			return null;
		}
		Map<String, HeldTransform> held = s.get(ViewModel.HELD);
		HeldTransform own = held.get(id);
		if (own == null && categories != 0) {
			for (Map.Entry<String, HeldTransform> e : held.entrySet()) {
				Category c = Category.of(e.getKey());
				if (c != null && (categories & c.bit()) != 0) {
					own = e.getValue();
					break;
				}
			}
		}
		if (own == null && s.bool(ViewModel.ITEMS)) {
			own = read(s, ViewModel.ITEM_TRANSFORM);
		}
		return own != null && own.changes() ? own : null;
	}

	/** The transform kept in the nine settings {@code parts}. */
	public static HeldTransform read(ModuleState s, List<FloatSetting> parts) {
		float[] v = new float[HeldTransform.SIZE];
		for (int i = 0; i < v.length; i++) {
			v[i] = s.decimal(parts.get(i));
		}
		return HeldTransform.of(v);
	}

	/** {@code t} into the nine settings {@code parts}; whether anything changed. */
	public static boolean write(ModuleState s, List<FloatSetting> parts, HeldTransform t) {
		boolean changed = false;
		for (int i = 0; i < HeldTransform.SIZE; i++) {
			changed |= s.set(parts.get(i), t.get(i));
		}
		return changed;
	}

	/**
	 * How many ticks your swing takes: Minecraft's {@code ticks}, without Haste's speed-up or Mining Fatigue's slow-down when
	 * they are ignored, then at the swing speed (at least one tick). {@code haste}/{@code fatigue}: the effect's level plus one
	 * (0 when you don't have it), as Minecraft counted it in {@code ticks}.
	 */
	public static int swingTicks(int ticks, int haste, int fatigue, boolean ignoreHaste, boolean ignoreFatigue, float speed) {
		int out = ticks;
		if (haste > 0 && ignoreHaste) {
			// Minecraft took Haste's levels off (and then never counted Mining Fatigue): give them back, then count it as it would.
			out += haste;
			if (fatigue > 0 && !ignoreFatigue) {
				out += fatigue * 2;
			}
		} else if (haste <= 0 && fatigue > 0 && ignoreFatigue) {
			out -= fatigue * 2;
		}
		if (speed != 1 && speed > 0) {
			out = Math.round(out / speed);
		}
		return Math.max(1, out);
	}

	/** Your swing's ticks with Item Customizer's animations ({@link #swingTicks}); {@code ticks} as they are when they don't apply. */
	public static int swingTicks(ModuleState s, int ticks, int haste, int fatigue) {
		if (!s.bool(ViewModel.ANIMATIONS)) {
			return ticks;
		}
		boolean ignoreHaste = s.bool(ViewModel.IGNORE_HASTE);
		boolean ignoreFatigue = s.bool(ViewModel.IGNORE_FATIGUE);
		float speed = s.bool(ViewModel.SWING_SPEED) ? s.decimal(ViewModel.SWING_SPEED_VALUE) : 1;
		if (!ignoreHaste && !ignoreFatigue && speed == 1) {
			return ticks;
		}
		return swingTicks(ticks, haste, fatigue, ignoreHaste, ignoreFatigue, speed);
	}

	/** A dropped item's size from Dropped Item Size: the selected size for a listed item, else every item's; 1 when it's off. */
	public static float droppedScale(ModuleState s, String id) {
		if (!s.bool(ViewModel.DROPPED)) {
			return 1;
		}
		List<String> listed = s.ids(ViewModel.DROPPED_ITEMS);
		if (!listed.isEmpty() && Collections.binarySearch(listed, id) >= 0) {
			return s.decimal(ViewModel.DROPPED_SELECTED_SCALE);
		}
		return s.decimal(ViewModel.DROPPED_SCALE);
	}

	/**
	 * The answers per item, kept until a setting changes: one per Minecraft line (its items are its keys). Render thread.
	 */
	public static final class Lookup {
		private static final HeldTransform NO_PLACE = new HeldTransform(0, 0, 0, 0, 0, 0, 1, 1, 1);
		private static final Float NO_SIZE = 1F;
		private final Facts facts;
		private final IdentityHashMap<Object, HeldTransform> places = new IdentityHashMap<>();
		private final IdentityHashMap<Object, Float> sizes = new IdentityHashMap<>();
		private @Nullable ModuleState state;
		private int revision = -1;
		private @Nullable HeldTransform hand;

		public Lookup(Facts facts) {
			this.facts = facts;
		}

		private void check(ModuleState s) {
			if (s != state || s.revision() != revision) {
				state = s;
				revision = s.revision();
				places.clear();
				sizes.clear();
				HeldTransform h = s.bool(ViewModel.FIRST_PERSON) && s.bool(ViewModel.EMPTY_HANDS) ? read(s, ViewModel.HAND_TRANSFORM) : null;
				hand = h != null && h.changes() ? h : null;
			}
		}

		/** {@code item}'s place ({@link #resolve}), or null for Minecraft's own. */
		public @Nullable HeldTransform place(ModuleState s, Object item) {
			check(s);
			HeldTransform t = places.get(item);
			if (t == null) {
				HeldTransform resolved = resolve(s, facts.id(item), facts.categories(item));
				t = resolved == null ? NO_PLACE : resolved;
				places.put(item, t);
			}
			return t == NO_PLACE ? null : t;
		}

		/** The empty hand's place, or null for Minecraft's own. */
		public @Nullable HeldTransform hand(ModuleState s) {
			check(s);
			return hand;
		}

		/** {@code item}'s size on the ground ({@link #droppedScale}). */
		public float dropped(ModuleState s, Object item) {
			check(s);
			Float size = sizes.get(item);
			if (size == null) {
				float k = droppedScale(s, facts.id(item));
				size = k == 1 ? NO_SIZE : Float.valueOf(k);
				sizes.put(item, size);
			}
			return size;
		}
	}
}

package com.flintclient.client.control.rshift;

import com.flintclient.client.control.feature.CosmeticKinds;
import com.flintclient.client.control.feature.CosmeticKinds.Category;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The wardrobe's rules, apart from its drawing ({@link WardrobeScreen}): which items a category shows, in what order, what a
 * search, sort and filter leave, the sidebar's counts and dots, and a 64×32 skin made 64×64 for the 3D player. Pure Java.
 */
final class WardrobeModel {
	private WardrobeModel() {
	}

	enum Sort {
		NEWEST("Newest"),
		NAME("Name"),
		EQUIPPED("Equipped first");

		final String label;

		Sort(String label) {
			this.label = label;
		}

		Sort next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	enum Filter {
		ALL("All"),
		OWNED("Owned"),
		LOCKED("Locked"),
		ANIMATED("Animated");

		final String label;

		Filter(String label) {
			this.label = label;
		}
	}

	/**
	 * The items {@code category} shows: what the account owns of its kinds (and, unless filtered out, the Store's others,
	 * locked), the search's matches only (and only animated ones with that filter), owned before locked, sorted (Equipped
	 * first: what is worn, then the newest). {@code worn} answers whether an item is worn now (the
	 * picks on their way included).
	 */
	static List<Item> items(@Nullable Cosmetics c, Category category, String query, Sort sort, Filter filter, Function<Item, Boolean> worn) {
		List<Item> out = new ArrayList<>();
		if (c == null || !category.store()) {
			return out;
		}
		String q = query.toLowerCase(Locale.ROOT).strip();
		if (filter != Filter.LOCKED) {
			for (Item item : c.items()) {
				if (CosmeticKinds.categoryOf(item.kind()) == category && matches(item, q) && (filter != Filter.ANIMATED || animated(item))) {
					out.add(item);
				}
			}
		}
		if (filter != Filter.OWNED) {
			for (Item item : c.shop()) {
				if (CosmeticKinds.categoryOf(item.kind()) == category && matches(item, q) && !owns(c, item.id()) && (filter != Filter.ANIMATED || animated(item))) {
					out.add(item);
				}
			}
		}
		// The Store's order is the newest-first fallback: an item without a date keeps its place among those without one.
		List<Item> store = new ArrayList<>(out);
		Comparator<Item> order = switch (sort) {
			case NEWEST -> Comparator.<Item>comparingLong(i -> -i.addedAt()).thenComparingInt(store::indexOf);
			case NAME -> Comparator.comparing((Item i) -> i.name().toLowerCase(Locale.ROOT)).thenComparingInt(store::indexOf);
			case EQUIPPED -> Comparator.<Item, Boolean>comparing(i -> !Boolean.TRUE.equals(worn.apply(i))).thenComparingLong(i -> -i.addedAt())
				.thenComparingInt(store::indexOf);
		};
		out.sort(Comparator.comparing((Item i) -> !i.owned()).thenComparing(order));
		return out;
	}

	private static boolean matches(Item item, String q) {
		return q.isEmpty() || item.name().toLowerCase(Locale.ROOT).contains(q);
	}

	private static boolean animated(Item item) {
		return item.animation() != null;
	}

	static boolean owns(Cosmetics c, String id) {
		for (Item item : c.items()) {
			if (item.id().equals(id)) {
				return true;
			}
		}
		return false;
	}

	/** The account's items of {@code category} (the sidebar's count; the Store's others aren't counted). */
	static int count(@Nullable Cosmetics c, Category category) {
		int n = 0;
		if (c != null) {
			for (Item item : c.items()) {
				n += CosmeticKinds.categoryOf(item.kind()) == category ? 1 : 0;
			}
		}
		return n;
	}

	/** Whether something of {@code category} is worn (the sidebar's green dot); the emote wheel counts when it has any. */
	static boolean wearsAny(@Nullable Cosmetics c, Category category, Map<String, String> wearing) {
		if (c == null) {
			return false;
		}
		if (category == Category.EMOTES) {
			return c.emotes() != null && c.emotes().stream().anyMatch(id -> !id.isEmpty());
		}
		for (String slot : CosmeticKinds.SLOTS) {
			CosmeticKinds.Kind kind = CosmeticKinds.ofSlot(slot);
			if (kind != null && kind.category() == category) {
				String putting = wearing.get(slot);
				String id = putting != null ? putting : c.worn(slot);
				return id != null && !id.isEmpty();
			}
		}
		return false;
	}

	/** The item {@code id} the account owns, or null. */
	static @Nullable Item owned(@Nullable Cosmetics c, @Nullable String id) {
		if (c == null || id == null || id.isEmpty()) {
			return null;
		}
		for (Item item : c.items()) {
			if (item.id().equals(id)) {
				return item;
			}
		}
		return null;
	}

	/**
	 * A skin's pixels as the 3D player takes them: a 64×64 skin as it is; a 64×32 (pre-1.8) skin made 64×64 the way Minecraft
	 * does it, its right leg and arm mirrored into the left ones (and its second layer of the head left as it was).
	 */
	static int[] modernSkin(int[] argb, int w, int h) {
		if (w != 64 || h != 32) {
			return argb;
		}
		int[] out = new int[64 * 64];
		System.arraycopy(argb, 0, out, 0, 64 * 32);
		// Minecraft's processLegacySkin: each face of the right leg (0,16) and arm (40,16) copied, mirrored, to the left ones.
		int[][] copies = {
			{4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12}, {8, 20, 8, 32, 4, 12},
			{12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4}, {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12},
			{48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}};
		for (int[] k : copies) {
			int sx = k[0];
			int sy = k[1];
			int dx = k[2];
			int dy = k[3];
			int cw = k[4];
			int ch = k[5];
			for (int y = 0; y < ch; y++) {
				for (int x = 0; x < cw; x++) {
					int from = (sy + y) * 64 + sx + x;
					int toX = sx + dx + (cw - 1 - x);
					int toY = sy + dy + y;
					out[toY * 64 + toX] = out[from];
				}
			}
		}
		return out;
	}

	/**
	 * How a community reward the account doesn't have unlocks, in words (it is earned, never bought): the card's line, the
	 * detail's kind tag and text, and the main button (null: nothing to do here). Null for an item for sale. The Flint Discord
	 * rewards stay only while the condition holds: Flint's server unlocks and locks them.
	 */
	record Unlock(String line, String tag, String body, @Nullable String button) {
	}

	static @Nullable Unlock unlock(Item item) {
		String u = item.unlock();
		if (item.owned() || u == null) {
			return null;
		}
		return switch (u) {
			case "discord_member" -> new Unlock("Join Discord", "DISCORD REWARD",
				"Join the Flint Discord to unlock. It stays yours while you're in the server.",
				"Join the Flint Discord");
			case "discord_booster" -> new Unlock("Boost Discord", "DISCORD REWARD",
				"Boost the Flint Discord to unlock. It stays yours while you boost.",
				"Boost the Flint Discord");
			case "first_launch" -> new Unlock("Reward", "COMMUNITY REWARD", "Unlocks the next time you sign in with the Flint app.", null);
			default -> new Unlock("Reward", "COMMUNITY REWARD", "A community reward: earned, not bought.", null);
		};
	}
}

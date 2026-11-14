package com.flintclient.client.control.rshift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.CosmeticKinds;
import com.flintclient.client.control.feature.CosmeticKinds.Category;
import com.flintclient.client.control.feature.Outfits;
import com.flintclient.client.control.rshift.WardrobeModel.Filter;
import com.flintclient.client.control.rshift.WardrobeModel.Sort;
import com.flintclient.client.module.social.SocialLink.Cosmetics;
import com.flintclient.client.module.social.SocialLink.Item;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WardrobeModelTest {
	private static Item item(int n, String kind, String name, boolean owned, long price, long addedAt) {
		return new Item(String.format("00000000-0000-4000-8000-%012d", n), name, kind, false, null, false, 1, null, owned, price, addedAt, null);
	}

	private static List<String> names(List<Item> items) {
		return items.stream().map(Item::name).toList();
	}

	private final Item ember = item(1, "cape", "Ember Cape", true, -1, 300);
	private final Item frost = item(2, "cape", "Frost Cape", true, -1, 200);
	private final Item aurora = item(3, "cape", "Aurora Cape", false, 500, 0);
	private final Item copper = item(4, "cape", "Copper Cape", false, 350, 0);
	private final Item wings = item(5, "wings", "Glass Wings", true, -1, 100);
	private final Item jet = item(6, "jetpack", "Jet Pack", true, -1, 50);
	private final Cosmetics c = new Cosmetics("https://store.flintclient.com", List.of(ember, frost, wings, jet), Map.of("cape", frost.id()), false, null, 3,
		List.of(aurora, copper, frost), List.of("", "", "", "", "", "", "", ""));

	@Test
	void aCategoryShowsWhatIsWornFirstThenOwnedThenTheStoresOthersLocked() {
		List<Item> shown = WardrobeModel.items(c, Category.CAPES, "", Sort.EQUIPPED, Filter.ALL, i -> i.id().equals(c.worn("cape")));
		assertEquals(List.of("Frost Cape", "Ember Cape", "Aurora Cape", "Copper Cape"), names(shown), "an owned item listed in the shop too is shown once");
		assertEquals(List.of("Ember Cape", "Frost Cape", "Aurora Cape", "Copper Cape"),
			names(WardrobeModel.items(c, Category.CAPES, "", Sort.NEWEST, Filter.ALL, i -> i.id().equals(c.worn("cape")))), "owned first, newest first");
		assertTrue(WardrobeModel.items(c, Category.CAPES, "", Sort.NAME, Filter.ANIMATED, i -> false).isEmpty(), "none of these is animated");
		assertEquals(List.of("Aurora Cape", "Copper Cape"), names(WardrobeModel.items(c, Category.CAPES, "", Sort.NAME, Filter.LOCKED, i -> false)));
		assertEquals(List.of("Ember Cape", "Frost Cape"), names(WardrobeModel.items(c, Category.CAPES, "", Sort.NAME, Filter.OWNED, i -> false)));
		assertEquals(List.of("Frost Cape"), names(WardrobeModel.items(c, Category.CAPES, "FRO", Sort.NAME, Filter.ALL, i -> false)), "the search ignores case");
		assertEquals(List.of("Jet Pack"), names(WardrobeModel.items(c, Category.MORE, "", Sort.NAME, Filter.ALL, i -> false)), "a kind Flint doesn't know yet");
		assertTrue(WardrobeModel.items(c, Category.OUTFITS, "", Sort.NAME, Filter.ALL, i -> false).isEmpty());
		assertTrue(WardrobeModel.items(null, Category.CAPES, "", Sort.NAME, Filter.ALL, i -> false).isEmpty());
	}

	@Test
	void theSidebarCountsOwnedItemsAndDotsWhatIsWorn() {
		assertEquals(2, WardrobeModel.count(c, Category.CAPES), "the Store's others aren't counted");
		assertEquals(1, WardrobeModel.count(c, Category.WINGS));
		assertTrue(WardrobeModel.wearsAny(c, Category.CAPES, Map.of()));
		assertFalse(WardrobeModel.wearsAny(c, Category.CAPES, Map.of("cape", "")), "a take-off on its way");
		assertTrue(WardrobeModel.wearsAny(c, Category.WINGS, Map.of("back", wings.id())), "a pick on its way");
		assertFalse(WardrobeModel.wearsAny(c, Category.EMOTES, Map.of()), "an empty wheel");
		assertNull(WardrobeModel.owned(c, aurora.id()));
	}

	@Test
	void everyKindHasOneSlotAndCategoryAndOlderNamesReadAsTodays() {
		for (String slot : CosmeticKinds.SLOTS) {
			assertEquals(slot, CosmeticKinds.ofSlot(slot).slot());
		}
		assertEquals("back", CosmeticKinds.slotOf("wings"));
		assertEquals("back", CosmeticKinds.slotOf("back"), "the Store's older name");
		assertEquals("head", CosmeticKinds.slotOf("head"));
		assertEquals("hands", CosmeticKinds.slotOf("gloves"));
		assertNull(CosmeticKinds.slotOf("emote"));
		assertNull(CosmeticKinds.slotOf("jetpack"));
		assertEquals(Category.MORE, CosmeticKinds.categoryOf("jetpack"));
		assertEquals(Category.BODYWEAR, CosmeticKinds.categoryOf("bodywear"));
		assertNull(CosmeticKinds.slotOf("bodywear"), "coming: nothing of it is worn yet");
		assertEquals("Hats", Category.HEADWEAR.title);
		assertTrue(Category.EMOTES.main() && !Category.BODYWEAR.main() && !Category.OUTFITS.main());
		assertEquals(Category.HEADWEAR, CosmeticKinds.categoryOf("head"));
		assertNull(CosmeticKinds.normalize("Boots"));
		assertNull(CosmeticKinds.normalize("x".repeat(33)));
		assertEquals("Jetpack v2", CosmeticKinds.label("jetpack_v2"));
		for (Category cat : Category.values()) {
			if (cat.store() && cat != Category.MORE && cat != Category.BODYWEAR) {
				assertEquals(cat, CosmeticKinds.categoryOf(CosmeticKinds.kindOf(cat)), cat.name());
			}
		}
	}

	@Test
	void theWardrobeIsOnFor263AndEveryNewerMinecraft() {
		for (String v : List.of("26.3", "26.3.1", "26.4", "26.4-snapshot-6", "27.1")) {
			assertTrue(CosmeticKinds.wardrobeOn(v), v);
		}
		for (String v : List.of("26.2", "26.1.2", "1.21.11", "1.8.9", "1.19", "", "x")) {
			assertFalse(CosmeticKinds.wardrobeOn(v), v);
		}
	}

	@Test
	void anOldSkinIsMadeModernWithItsRightLegAndArmMirroredIntoTheLeftOnes() {
		int[] old = new int[64 * 32];
		old[20 * 64 + 0] = 0xFF112233; // the right leg's outer side, top left
		old[20 * 64 + 44] = 0xFF445566; // the right arm's front, top left
		int[] modern = WardrobeModel.modernSkin(old, 64, 32);
		assertEquals(64 * 64, modern.length);
		assertEquals(0xFF112233, modern[52 * 64 + 24 + 3], "mirrored into the left leg's outer side");
		assertEquals(0xFF445566, modern[52 * 64 + 36 + 3], "mirrored into the left arm's front");
		int[] already = new int[64 * 64];
		assertArrayEquals(already, WardrobeModel.modernSkin(already, 64, 64));
	}

	@Test
	void outfitsAreSavedNewestFirstAndCapped() {
		Outfits o = new Outfits(null);
		String me = "0123456789abcdef0123456789abcdef";
		assertEquals("Outfit 1", o.nextName(me));
		assertTrue(o.save(me, "Outfit 1", Map.of("cape", ember.id()), 1));
		assertTrue(o.save(me, "Red§l night", Map.of("cape", frost.id(), "back", wings.id()), 2));
		assertEquals("Outfit 2", o.nextName(me));
		assertEquals("Redl night", o.of(me).get(0).name(), "no formatting codes");
		assertEquals("", o.of(me).get(0).slots().get("head"), "a slot it doesn't wear is left empty");
		for (int i = 0; i < Outfits.MAX; i++) {
			o.save(me, "x", Map.of(), 3);
		}
		assertEquals(Outfits.MAX, o.of(me).size());
		assertFalse(o.save(me, "one more", Map.of(), 4));
		o.remove(me, o.of(me).get(0));
		assertEquals(Outfits.MAX - 1, o.of(me).size());
	}

	/** The window block for a pxW × pxH game window, as WindowScreen lays it out for the Cosmetics window (1500 × 840 at most). */
	private static float[] block(int pxW, int pxH) {
		int s = MenuStyle.apply(pxW, pxH);
		float w = pxW / (float) s;
		float h = pxH / (float) s;
		float side = MenuStyle.d(44) + MenuStyle.d(14);
		float winW = Math.min(w - 2 * MenuStyle.d(24) - 2 * side, MenuStyle.d(1500));
		float winH = Math.max(Math.min(MenuStyle.d(460), h - 2 * MenuStyle.d(24)), Math.min(MenuStyle.d(840), h - 2 * MenuStyle.d(24)));
		return new float[] {winW, winH, s, w, h};
	}

	@Test
	void theWardrobeFitsEveryWindowFromSmallToUltrawideAndFourK() {
		int[][] sizes = {{854, 480}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1080}, {2560, 1440}, {3440, 1440}, {3840, 2160}, {5120, 2880},
			{1024, 768}, {800, 600}};
		for (int[] size : sizes) {
			float[] b = block(size[0], size[1]);
			float winW = b[0];
			float winH = b[1];
			String at = size[0] + "x" + size[1];
			assertTrue(winW > 0 && winW <= b[3] && winH <= b[4], at + ": the window is inside the screen");
			WardrobePage.Columns cols = WardrobePage.columns(winW);
			float used = 2 * cols.pad() + cols.side() + cols.centre() + cols.load() + 2 * MenuStyle.d(16);
			assertTrue(Math.abs(used - winW) < 0.01F, at + ": the columns fill the window exactly (" + used + " of " + winW + ")");
			assertTrue(cols.centre() >= MenuStyle.d(280) && cols.load() >= MenuStyle.d(170) && cols.side() > 0, at + ": every column keeps its room " + cols);
			// Height: the header, then the loadout's caption, its smallest stage and its button (the lists scroll).
			float body = winH - 2 * cols.pad() - MenuStyle.d(56);
			assertTrue(body >= MenuStyle.d(40) + MenuStyle.d(140) + MenuStyle.d(40) + MenuStyle.d(20), at + ": the loadout fits its stage and button (" + body + ")");
		}
	}

	@Test
	void compactCardsFiveOrSixToARowAt1080p() {
		int at1080 = cardsPerRow(1920, 1080);
		assertTrue(at1080 >= 5 && at1080 <= 6, "1920x1080: " + at1080);
		int at720 = cardsPerRow(1280, 720);
		assertTrue(at720 >= 3 && at720 <= 5, "1280x720: " + at720);
		for (int[] size : new int[][] {{854, 480}, {3440, 1440}, {3840, 2160}}) {
			int n = cardsPerRow(size[0], size[1]);
			assertTrue(n >= 2 && n <= 8, size[0] + "x" + size[1] + ": " + n);
		}
	}

	/** As the grid counts them: cards about 136 window pixels wide at Large (less at a smaller Menu size), 12 design pixels apart. */
	private static int cardsPerRow(int pxW, int pxH) {
		float[] b = block(pxW, pxH);
		WardrobePage.Columns cols = WardrobePage.columns(b[0]);
		int scale = (int) b[2];
		int px = Math.round((cols.centre() - 2 * MenuStyle.d(4)) * scale);
		int gap = Math.round(MenuStyle.d(12) * scale);
		return Math.max(2, Math.min(8, (px + gap) / (WardrobePage.cardPx() + gap)));
	}

	@Test
	void aDiscordRewardSaysHowItUnlocksNeverTheStore() {
		Item member = new Item("00000000-0000-4000-8000-000000000901", "Flint Bound", "cape", false, null, false, 1, null, false, -1, 0, null, "discord_member");
		Item booster = new Item("00000000-0000-4000-8000-000000000902", "Overcharged", "cape", false, null, false, 1, null, false, -1, 0, null, "discord_booster");
		WardrobeModel.Unlock m = WardrobeModel.unlock(member);
		WardrobeModel.Unlock b = WardrobeModel.unlock(booster);
		assertTrue(m != null && m.body().startsWith("Join the Flint Discord to unlock") && "Join the Flint Discord".equals(m.button()));
		assertTrue(b != null && b.body().startsWith("Boost the Flint Discord to unlock") && "Boost the Flint Discord".equals(b.button()));
		assertNull(WardrobeModel.unlock(new Item("00000000-0000-4000-8000-000000000903", "Frost", "cape", false, null, false, 1, null, false, 400, 0, null)),
			"an item for sale is the Store's");
		assertNull(WardrobeModel.unlock(new Item("00000000-0000-4000-8000-000000000904", "Mine", "cape", false, null, false, 1, null, true, -1, 0, null, "discord_member")),
			"an owned reward has nothing to unlock");
	}
}

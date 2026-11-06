package com.flintclient.legacy.mods;

import com.flintclient.legacy.module.HudBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;

/**
 * How many of the items that matter in a fight you carry, for the shared Item Counter view: arrows, golden apples, ender
 * pearls, firework rockets, building blocks, and the shared "More items" list (ids such as {@code minecraft:mushroom_stew};
 * {@code minecraft:splash_potion} counts 1.8.9's splash potions). 1.8.9 has no totems, so that group isn't offered. Counted
 * once a tick over your inventory (the shared view reads the counts through {@code platform.HudData}).
 */
public final class ItemCounter extends HudBackend {
	private static final String SPLASH = "minecraft:splash_potion";
	/** The shared groups (arrows, golden apples, pearls, totems, fireworks, blocks); 1.8.9 has no totems. */
	private static final int GROUPS = 6;
	private static final int TOTEMS = 3;
	private static final int[] GROUP_COUNTS = new int[GROUPS];
	private static ItemStack[] groupIcons;
	private static final List<Item> EXTRAS = new ArrayList<Item>();
	private static final List<Boolean> EXTRA_SPLASH = new ArrayList<Boolean>();
	private static final List<ItemStack> EXTRA_ICONS = new ArrayList<ItemStack>();
	private static int[] extraCounts = new int[0];
	private static String extrasParsed;

	public ItemCounter() {
		super("item_counter", "itemcounter", "totems");
	}

	private static ItemStack[] groupIcons() {
		if (groupIcons == null) {
			groupIcons = new ItemStack[] {new ItemStack(Items.ARROW), new ItemStack(Items.GOLDEN_APPLE), new ItemStack(Items.ENDER_PEARL), null,
				new ItemStack(Items.FIREWORKS), new ItemStack(net.minecraft.block.Blocks.WOOL)};
		}
		return groupIcons;
	}

	public static int groupCount(int group) {
		return group >= 0 && group < GROUPS ? GROUP_COUNTS[group] : 0;
	}

	/** The group's icon, or null for a group 1.8.9 doesn't have (totems). */
	public static ItemStack groupIcon(int group) {
		return group >= 0 && group < GROUPS ? groupIcons()[group] : null;
	}

	/** Reads the "More items" list again when it changed; returns how many kinds it names. */
	public static int setExtraItems(String text) {
		if (!text.equals(extrasParsed)) {
			extrasParsed = text;
			EXTRAS.clear();
			EXTRA_SPLASH.clear();
			EXTRA_ICONS.clear();
			for (String raw : text.split("[,\\s]+")) {
				String id = raw.trim().toLowerCase(Locale.ROOT);
				if (id.isEmpty()) {
					continue;
				}
				if (id.equals(SPLASH)) {
					EXTRAS.add(Items.POTION);
					EXTRA_SPLASH.add(Boolean.TRUE);
					EXTRA_ICONS.add(new ItemStack(Items.POTION, 1, 16421));
					continue;
				}
				Item item = Item.byKey(id.contains(":") ? id : "minecraft:" + id);
				if (item != null && !EXTRAS.contains(item)) {
					EXTRAS.add(item);
					EXTRA_SPLASH.add(Boolean.FALSE);
					EXTRA_ICONS.add(new ItemStack(item));
				}
			}
			extraCounts = new int[EXTRAS.size()];
			count();
		}
		return EXTRAS.size();
	}

	public static int extraCount(int index) {
		return index >= 0 && index < extraCounts.length ? extraCounts[index] : 0;
	}

	public static ItemStack extraIcon(int index) {
		return index >= 0 && index < EXTRA_ICONS.size() ? EXTRA_ICONS.get(index) : null;
	}

	/** Which group or extra kind {@code s} counts for: 0..5 a group, 6 + i extra kind i, -1 none. */
	private static int kind(ItemStack s) {
		Item item = s.getItem();
		for (int i = 0; i < EXTRAS.size(); i++) {
			if (EXTRAS.get(i) == item && (!EXTRA_SPLASH.get(i) || PotionItem.isSplashPotion(s.getMetadata()))) {
				return GROUPS + i;
			}
		}
		if (item == Items.ARROW) {
			return 0;
		}
		if (item == Items.GOLDEN_APPLE) {
			return 1;
		}
		if (item == Items.ENDER_PEARL) {
			return 2;
		}
		if (item == Items.FIREWORKS) {
			return 4;
		}
		if (item instanceof BlockItem) {
			return 5;
		}
		return -1;
	}

	private static void count() {
		java.util.Arrays.fill(GROUP_COUNTS, 0);
		java.util.Arrays.fill(extraCounts, 0);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		for (ItemStack s : mc.player.inventory.items) {
			if (s != null) {
				int k = kind(s);
				if (k >= GROUPS) {
					extraCounts[k - GROUPS] += s.size;
				} else if (k >= 0 && k != TOTEMS) {
					GROUP_COUNTS[k] += s.size;
				}
			}
		}
	}

	@Override
	public void tick() {
		count();
	}
}

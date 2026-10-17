package com.flintclient.client.control.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of what Flint's information counters read: the world's day, your view angles, the height
 * limits, food, inventory counts, item cooldowns and horse attributes. Everything is something the game already shows
 * or synced to you. Inventory counts are recomputed only when the inventory changes (its change counter), so a frame
 * costs a field compare. Render thread only.
 */
public final class Mc263GameData {
	/** Groups the Item Counter tracks, in display order. */
	public static final int ARROWS = 0;
	public static final int GOLDEN_APPLES = 1;
	public static final int PEARLS = 2;
	public static final int TOTEMS = 3;
	public static final int FIREWORKS = 4;
	public static final int BLOCKS = 5;
	public static final int GROUPS = 6;

	private static final ItemStack[] GROUP_ICONS = new ItemStack[GROUPS];
	private static final int[] GROUP_COUNTS = new int[GROUPS];
	private static final int[] POTION_COUNTS = new int[3];
	private static int countedChange = -1;
	private static @Nullable Inventory countedInventory;

	/** "More items" as typed, and the items it names (unknown ids are left out). */
	private static String extraText = "";
	private static final List<Item> EXTRA_ITEMS = new ArrayList<>();
	private static final List<ItemStack> EXTRA_ICONS = new ArrayList<>();
	private static int[] extraCounts = new int[0];
	private static int extraChange = -1;

	private Mc263GameData() {
	}

	private static @Nullable LocalPlayer player() {
		return Minecraft.getInstance().player;
	}

	public static boolean inWorld() {
		return Minecraft.getInstance().level != null && player() != null;
	}

	/** The overworld clock in ticks (Minecraft's "day time"), or -1 outside a world. */
	public static long dayTicks() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? -1 : level.getOverworldClockTime();
	}

	public static float yaw() {
		LocalPlayer p = player();
		return p == null ? Float.NaN : p.getYRot();
	}

	public static float pitch() {
		LocalPlayer p = player();
		return p == null ? Float.NaN : p.getXRot();
	}

	public static double y() {
		LocalPlayer p = player();
		return p == null ? Double.NaN : p.getY();
	}

	/** The lowest buildable y of the current dimension. */
	public static int minY() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getMinY();
	}

	/** The highest buildable y (inclusive). */
	public static int maxY() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getMaxY();
	}

	public static float saturation() {
		LocalPlayer p = player();
		return p == null ? Float.NaN : p.getFoodData().getSaturationLevel();
	}

	public static int food() {
		LocalPlayer p = player();
		return p == null ? -1 : p.getFoodData().getFoodLevel();
	}

	public static boolean sprinting() {
		LocalPlayer p = player();
		return p != null && p.isSprinting();
	}

	public static boolean sneaking() {
		LocalPlayer p = player();
		return p != null && p.isShiftKeyDown();
	}

	public static boolean flying() {
		LocalPlayer p = player();
		return p != null && p.getAbilities().flying;
	}

	// ---------- inventory ----------

	private static void recount() {
		LocalPlayer p = player();
		if (p == null) {
			countedInventory = null;
			return;
		}
		Inventory inventory = p.getInventory();
		int change = inventory.getTimesChanged();
		if (inventory == countedInventory && change == countedChange) {
			return;
		}
		countedInventory = inventory;
		countedChange = change;
		java.util.Arrays.fill(GROUP_COUNTS, 0);
		java.util.Arrays.fill(POTION_COUNTS, 0);
		int[] extra = extraCounts.length == EXTRA_ITEMS.size() ? extraCounts : new int[EXTRA_ITEMS.size()];
		java.util.Arrays.fill(extra, 0);
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			int n = stack.getCount();
			if (stack.is(ItemTags.ARROWS)) {
				GROUP_COUNTS[ARROWS] += n;
			} else if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
				GROUP_COUNTS[GOLDEN_APPLES] += n;
			} else if (stack.is(Items.ENDER_PEARL)) {
				GROUP_COUNTS[PEARLS] += n;
			} else if (stack.is(Items.TOTEM_OF_UNDYING)) {
				GROUP_COUNTS[TOTEMS] += n;
			} else if (stack.is(Items.FIREWORK_ROCKET)) {
				GROUP_COUNTS[FIREWORKS] += n;
			} else if (stack.getItem() instanceof BlockItem) {
				GROUP_COUNTS[BLOCKS] += n;
			}
			PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
			if (potion != null) {
				POTION_COUNTS[2] += n;
				if (potion.is(Potions.HEALING) || potion.is(Potions.STRONG_HEALING)) {
					POTION_COUNTS[1] += n;
					if (stack.is(Items.SPLASH_POTION)) {
						POTION_COUNTS[0] += n;
					}
				}
			}
			for (int i = 0; i < EXTRA_ITEMS.size(); i++) {
				if (stack.is(EXTRA_ITEMS.get(i))) {
					extra[i] += n;
				}
			}
		}
		extraCounts = extra;
	}

	/** Potions in the inventory: 0 splash healing, 1 all healing, 2 all potions. -1 outside a world. */
	public static int potions(int kind) {
		recount();
		return countedInventory == null ? -1 : POTION_COUNTS[kind];
	}

	/** How many items of {@code group} you carry. */
	public static int groupCount(int group) {
		recount();
		return countedInventory == null ? 0 : GROUP_COUNTS[group];
	}

	public static ItemStack groupIcon(int group) {
		ItemStack icon = GROUP_ICONS[group];
		if (icon == null) {
			Item item = switch (group) {
				case ARROWS -> Items.ARROW;
				case GOLDEN_APPLES -> Items.GOLDEN_APPLE;
				case PEARLS -> Items.ENDER_PEARL;
				case TOTEMS -> Items.TOTEM_OF_UNDYING;
				case FIREWORKS -> Items.FIREWORK_ROCKET;
				default -> Items.COBBLESTONE;
			};
			icon = new ItemStack(item);
			GROUP_ICONS[group] = icon;
		}
		return icon;
	}

	/** Reads "More items" (comma-separated ids) when it changed; unknown ids are skipped. Returns how many it names. */
	public static int setExtraItems(String text) {
		if (!text.equals(extraText)) {
			extraText = text;
			EXTRA_ITEMS.clear();
			EXTRA_ICONS.clear();
			for (String part : text.split(",")) {
				String id = part.strip();
				if (id.isEmpty()) {
					continue;
				}
				Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
				Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
				if (item != null && item != Items.AIR && !EXTRA_ITEMS.contains(item) && EXTRA_ITEMS.size() < 12) {
					EXTRA_ITEMS.add(item);
					EXTRA_ICONS.add(new ItemStack(item));
				}
			}
			countedChange = -1;
			extraChange++;
		}
		return EXTRA_ITEMS.size();
	}

	public static int extraCount(int index) {
		recount();
		return countedInventory == null || index >= extraCounts.length ? 0 : extraCounts[index];
	}

	public static ItemStack extraIcon(int index) {
		return EXTRA_ICONS.get(index);
	}

	// ---------- cooldowns ----------

	/**
	 * Visits the distinct items in your inventory that are cooling down, at most {@code max}: their stack (for the icon),
	 * the share left (0 to 1) and the ticks left when known (-1 if not). Returns how many it wrote.
	 */
	public static int cooldowns(ItemStack[] stacks, float[] share, int[] ticksLeft, int max, float partialTick) {
		LocalPlayer p = player();
		if (p == null) {
			return 0;
		}
		ItemCooldowns cooldowns = p.getCooldowns();
		Inventory inventory = p.getInventory();
		int n = 0;
		for (int slot = 0; slot < inventory.getContainerSize() && n < max; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !cooldowns.isOnCooldown(stack)) {
				continue;
			}
			boolean seen = false;
			for (int i = 0; i < n && !seen; i++) {
				seen = cooldowns.getCooldownGroup(stacks[i]).equals(cooldowns.getCooldownGroup(stack));
			}
			if (seen) {
				continue;
			}
			stacks[n] = stack;
			share[n] = cooldowns.getCooldownPercent(stack, partialTick);
			int total = com.flintclient.client.control.feature.CooldownDurations.ticks(cooldowns.getCooldownGroup(stack).toString());
			ticksLeft[n] = total > 0 ? Math.round(share[n] * total) : -1;
			n++;
		}
		return n;
	}

	// ---------- horses ----------

	/**
	 * The horse you ride, else (when {@code looking}) the one under your crosshair, as speed (blocks/s), jump height
	 * (blocks), health and max health into {@code out}. False when there is none.
	 */
	public static boolean horse(boolean looking, double[] out) {
		LocalPlayer p = player();
		if (p == null) {
			return false;
		}
		Entity target = p.getVehicle();
		if (!(target instanceof AbstractHorse) && looking) {
			HitResult hit = Minecraft.getInstance().hitResult;
			target = hit instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
		}
		if (!(target instanceof AbstractHorse horse)) {
			return false;
		}
		out[0] = horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * 42.16;
		out[1] = jumpHeight(horse.getAttributeValue(Attributes.JUMP_STRENGTH));
		out[2] = horse.getHealth();
		out[3] = horse.getMaxHealth();
		return true;
	}

	/** The peak height of a jump with this initial upward speed, stepping Minecraft's per-tick physics (gravity 0.08, drag 0.98). */
	static double jumpHeight(double strength) {
		double height = 0;
		double velocity = strength;
		while (velocity > 0) {
			height += velocity;
			velocity = (velocity - 0.08) * 0.98;
		}
		return height;
	}
}

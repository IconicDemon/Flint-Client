package com.flintclient.client.compat.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Unit;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The item data components the shared sources read (1.20.5 moved an item's data from its NBT into these), each read from
 * Minecraft 1.19.2's item and NBT so it answers as 1.20.5 does: the components every item has (lore, enchantments) are
 * there, empty, on any item; those of a kind of item (a shulker box's contents, a bundle's, a potion's, a book's stored
 * enchantments, a food's values) on that kind, and on any item whose NBT carries them. A stack answers them through
 * {@link ItemStackBridge} (stack.get, has, getOrDefault, set).
 */
public final class DataComponents {
	/** A container's items: a shulker box's (empty or not), and any block item whose block entity NBT keeps items. */
	public static final DataComponentType<ItemContainerContents> CONTAINER = new DataComponentType<>("container", ItemContainerContents::read,
		ItemContainerContents::present, null);
	/** A bundle's items. */
	public static final DataComponentType<BundleContents> BUNDLE_CONTENTS = new DataComponentType<>("bundle_contents", BundleContents::read,
		BundleContents::present, null);
	/** The resource pack model number 1.19.2 keeps as CustomModelData. */
	public static final DataComponentType<Integer> CUSTOM_MODEL_DATA = new DataComponentType<>("custom_model_data",
		stack -> hasCustomModelData(stack) ? stack.getTag().getInt("CustomModelData") : null, DataComponents::hasCustomModelData, null);
	/** An item's enchantments (every item has them, empty when it has none). */
	public static final DataComponentType<ItemEnchantments> ENCHANTMENTS = new DataComponentType<>("enchantments",
		stack -> ItemEnchantments.of(stack.getEnchantmentTags()), stack -> true, null);
	/** An enchanted book's stored enchantments. */
	public static final DataComponentType<ItemEnchantments> STORED_ENCHANTMENTS = new DataComponentType<>("stored_enchantments",
		stack -> hasStored(stack) ? ItemEnchantments.of(EnchantedBookItem.getEnchantments(stack)) : null, DataComponents::hasStored, null);
	/** A food's values (any item 1.19.2 lets you eat). */
	public static final DataComponentType<FoodProperties> FOOD = new DataComponentType<>("food",
		stack -> FoodProperties.of(stack.getItem().getFoodProperties()), stack -> stack.getItem().getFoodProperties() != null, null);
	/** 1.20.5's switch that hides an item's whole tooltip: 1.19.2 has no such thing (its HideFlags hide parts), so no item has it. */
	public static final DataComponentType<Unit> HIDE_TOOLTIP = new DataComponentType<>("hide_tooltip", stack -> null, stack -> false, null);
	/** An item's lore lines (every item has them, none when it has none). */
	public static final DataComponentType<ItemLore> LORE = new DataComponentType<>("lore", ItemLore::read, stack -> true, null);
	/** A potion's (or tipped arrow's) potion, colour and extra effects. Flint sets these on the potions it draws. */
	public static final DataComponentType<PotionContents> POTION_CONTENTS = new DataComponentType<>("potion_contents", PotionContents::read,
		PotionContents::present, PotionContents::write);

	private DataComponents() {
	}

	private static boolean hasCustomModelData(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("CustomModelData", Tag.TAG_ANY_NUMERIC);
	}

	private static boolean hasStored(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return stack.is(Items.ENCHANTED_BOOK) || tag != null && tag.contains(EnchantedBookItem.TAG_STORED_ENCHANTMENTS, Tag.TAG_LIST);
	}
}

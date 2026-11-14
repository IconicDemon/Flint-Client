package com.flintclient.client.compat.item;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 1.20.5's enchantments component over Minecraft 1.19.2's enchantment list (an item's Enchantments, a book's
 * StoredEnchantments): each enchantment by its registry holder, with its level, in the order the item keeps them (the order
 * 1.19.2's tooltip lists them in).
 */
public final class ItemEnchantments {
	public static final ItemEnchantments EMPTY = new ItemEnchantments(new Object2IntLinkedOpenHashMap<>());

	private final Object2IntLinkedOpenHashMap<Holder<Enchantment>> enchantments;

	private ItemEnchantments(Object2IntLinkedOpenHashMap<Holder<Enchantment>> enchantments) {
		this.enchantments = enchantments;
	}

	/** The enchantments of an NBT list as 1.19.2 keeps them (an id and a level each); unknown ids are left out. */
	public static ItemEnchantments of(ListTag list) {
		return list.isEmpty() ? EMPTY : of(EnchantmentHelper.deserializeEnchantments(list));
	}

	/** The enchantments of a map from 1.19.2's EnchantmentHelper (levels of 0 or less are none, as 1.20.5 has them). */
	public static ItemEnchantments of(Map<Enchantment, Integer> levels) {
		if (levels.isEmpty()) {
			return EMPTY;
		}
		Object2IntLinkedOpenHashMap<Holder<Enchantment>> out = new Object2IntLinkedOpenHashMap<>(levels.size());
		for (Map.Entry<Enchantment, Integer> e : levels.entrySet()) {
			if (e.getValue() > 0) {
				out.put(holder(e.getKey()), (int) e.getValue());
			}
		}
		return out.isEmpty() ? EMPTY : new ItemEnchantments(out);
	}

	/**
	 * 1.21's EnchantmentHelper.getEnchantmentsForCrafting: an enchanted book's stored enchantments, any other item's own (the
	 * list 1.19.2's EnchantmentHelper.getEnchantments reads; an item without any costs no map).
	 */
	public static ItemEnchantments forCrafting(ItemStack stack) {
		return of(stack.is(Items.ENCHANTED_BOOK) ? EnchantedBookItem.getEnchantments(stack) : stack.getEnchantmentTags());
	}

	/** An enchantment's registry holder (a free-standing one for an enchantment the registry doesn't know). */
	public static Holder<Enchantment> holder(Enchantment enchantment) {
		return Registry.ENCHANTMENT.getResourceKey(enchantment).flatMap(Registry.ENCHANTMENT::getHolder)
			.orElseGet(() -> Holder.direct(enchantment));
	}

	/** The level of {@code enchantment}, or 0 when the item doesn't have it. */
	public int getLevel(Holder<Enchantment> enchantment) {
		return enchantments.getInt(enchantment);
	}

	public Set<Holder<Enchantment>> keySet() {
		return Collections.unmodifiableSet(enchantments.keySet());
	}

	public Set<Object2IntMap.Entry<Holder<Enchantment>>> entrySet() {
		return Collections.unmodifiableSet(enchantments.object2IntEntrySet());
	}

	public int size() {
		return enchantments.size();
	}

	public boolean isEmpty() {
		return enchantments.isEmpty();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ItemEnchantments e && enchantments.equals(e.enchantments);
	}

	@Override
	public int hashCode() {
		return enchantments.hashCode();
	}

	@Override
	public String toString() {
		return "ItemEnchantments{" + enchantments + "}";
	}
}

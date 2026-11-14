package com.flintclient.client.compat.item;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.5's bundle contents over Minecraft 1.19.2's bundle NBT (its Items list, the last one put in first): every bundle has
 * them, empty when it holds nothing.
 */
public final class BundleContents {
	public static final BundleContents EMPTY = new BundleContents(List.of());

	private final List<ItemStack> items;

	private BundleContents(List<ItemStack> items) {
		this.items = items;
	}

	/** The items, each a copy. */
	public Stream<ItemStack> itemCopyStream() {
		return items.stream().map(ItemStack::copy);
	}

	/** The items (not copies: don't change them). */
	public Iterable<ItemStack> items() {
		return items;
	}

	public int size() {
		return items.size();
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}

	static boolean present(ItemStack stack) {
		return stack.getItem() instanceof BundleItem;
	}

	static @Nullable BundleContents read(ItemStack stack) {
		if (!present(stack)) {
			return null;
		}
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("Items", Tag.TAG_LIST)) {
			return EMPTY;
		}
		ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
		List<ItemStack> items = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			ItemStack item = ItemStack.of(list.getCompound(i));
			if (!item.isEmpty()) {
				items.add(item);
			}
		}
		return items.isEmpty() ? EMPTY : new BundleContents(List.copyOf(items));
	}
}

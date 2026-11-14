package com.flintclient.client.compat.item;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jspecify.annotations.Nullable;

/**
 * 1.20.5's container contents over Minecraft 1.19.2's block entity NBT (BlockEntityTag.Items, each item with its Slot): the
 * slots up to the last filled one, each in its place. A shulker box has them even when empty, as in 1.20.5; another block
 * item when its NBT keeps items (a chest picked up with its contents).
 */
public final class ItemContainerContents {
	/** 1.20.5's most slots a container item keeps. */
	private static final int MAX_SIZE = 256;
	public static final ItemContainerContents EMPTY = new ItemContainerContents(List.of());

	private final List<ItemStack> items;

	private ItemContainerContents(List<ItemStack> items) {
		this.items = items;
	}

	/** Every slot up to the last filled one (empty ones as empty stacks), each a copy. */
	public Stream<ItemStack> allItemsCopyStream() {
		return items.stream().map(ItemStack::copy);
	}

	/** The filled slots' items, each a copy. */
	public Stream<ItemStack> nonEmptyItemCopyStream() {
		return items.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy);
	}

	/** The filled slots' items (not copies: don't change them). */
	public Iterable<ItemStack> nonEmptyItems() {
		return () -> items.stream().filter(stack -> !stack.isEmpty()).iterator();
	}

	static boolean present(ItemStack stack) {
		if (stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
			return true;
		}
		CompoundTag tag = BlockItem.getBlockEntityData(stack);
		return tag != null && tag.contains("Items", Tag.TAG_LIST);
	}

	static @Nullable ItemContainerContents read(ItemStack stack) {
		if (!present(stack)) {
			return null;
		}
		CompoundTag tag = BlockItem.getBlockEntityData(stack);
		if (tag == null || !tag.contains("Items", Tag.TAG_LIST)) {
			return EMPTY;
		}
		ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
		int size = 0;
		for (int i = 0; i < list.size(); i++) {
			int slot = list.getCompound(i).getByte("Slot") & 255;
			if (slot < MAX_SIZE) {
				size = Math.max(size, slot + 1);
			}
		}
		NonNullList<ItemStack> slots = NonNullList.withSize(size, ItemStack.EMPTY);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag item = list.getCompound(i);
			int slot = item.getByte("Slot") & 255;
			if (slot < size) {
				slots.set(slot, ItemStack.of(item));
			}
		}
		int last = slots.size();
		while (last > 0 && slots.get(last - 1).isEmpty()) {
			last--;
		}
		return last == 0 ? EMPTY : new ItemContainerContents(List.copyOf(slots.subList(0, last)));
	}
}

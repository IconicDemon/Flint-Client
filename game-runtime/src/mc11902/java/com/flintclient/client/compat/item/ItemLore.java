package com.flintclient.client.compat.item;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 1.20.5's lore component over Minecraft 1.19.2's display.Lore (a list of text as JSON): its lines as written, without the
 * tooltip's purple italics; a line that isn't valid text is left out (1.19.2's tooltip drops the lore then).
 */
public record ItemLore(List<Component> lines) {
	public static final ItemLore EMPTY = new ItemLore(List.of());

	static ItemLore read(ItemStack stack) {
		CompoundTag display = stack.getTagElement(ItemStack.TAG_DISPLAY);
		if (display == null || display.getTagType(ItemStack.TAG_LORE) != Tag.TAG_LIST) {
			return EMPTY;
		}
		ListTag list = display.getList(ItemStack.TAG_LORE, Tag.TAG_STRING);
		if (list.isEmpty()) {
			return EMPTY;
		}
		List<Component> lines = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			try {
				Component line = Component.Serializer.fromJson(list.getString(i));
				if (line != null) {
					lines.add(line);
				}
			} catch (RuntimeException e) {
				// Not valid text: left out.
			}
		}
		return new ItemLore(List.copyOf(lines));
	}
}

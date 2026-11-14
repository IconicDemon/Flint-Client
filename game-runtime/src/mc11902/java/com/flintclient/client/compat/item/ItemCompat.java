package com.flintclient.client.compat.item;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.HorseArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShieldItem;

/** What 1.19.4 to 1.21 ask of an item, a dye or an attribute that Minecraft 1.19.2 answers differently. */
public final class ItemCompat {
	private ItemCompat() {
	}

	/**
	 * Whether the item is worn or held as equipment, as 1.21.1's Equipable items are (1.19.4 added the interface): armor
	 * (a horse's too, 1.21.1's animal armor), elytra and shields.
	 */
	public static boolean equipable(Item item) {
		return item instanceof ArmorItem || item instanceof ElytraItem || item instanceof ShieldItem || item instanceof HorseArmorItem;
	}

	/** A dye's texture colour as 1.21's opaque ARGB (1.19.2 keeps it as three floats made from the same RGB). */
	public static int textureDiffuseColor(DyeColor dye) {
		float[] rgb = dye.getTextureDiffuseColors();
		return 0xFF000000 | Math.round(rgb[0] * 255.0F) << 16 | Math.round(rgb[1] * 255.0F) << 8 | Math.round(rgb[2] * 255.0F);
	}

	/** An attribute's registry holder, as 1.20.5 names attributes (a free-standing one for an attribute the registry doesn't know). */
	public static Holder<Attribute> holder(Attribute attribute) {
		return Registry.ATTRIBUTE.getResourceKey(attribute).flatMap(Registry.ATTRIBUTE::getHolder).orElseGet(() -> Holder.direct(attribute));
	}
}

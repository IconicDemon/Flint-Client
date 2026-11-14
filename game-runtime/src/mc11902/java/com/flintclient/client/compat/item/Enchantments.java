package com.flintclient.client.compat.item;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * The enchantments the shared sources name, as 1.21's registry keys (1.21 made enchantments data; Minecraft 1.19.2's
 * Enchantments holds the enchantments themselves). A holder from {@link ItemEnchantments} is one of these when
 * {@code holder.is(key)}.
 */
public final class Enchantments {
	public static final ResourceKey<Enchantment> PROTECTION = key("protection");
	public static final ResourceKey<Enchantment> FIRE_PROTECTION = key("fire_protection");
	public static final ResourceKey<Enchantment> BLAST_PROTECTION = key("blast_protection");
	public static final ResourceKey<Enchantment> PROJECTILE_PROTECTION = key("projectile_protection");
	public static final ResourceKey<Enchantment> THORNS = key("thorns");
	public static final ResourceKey<Enchantment> MENDING = key("mending");
	public static final ResourceKey<Enchantment> UNBREAKING = key("unbreaking");

	private Enchantments() {
	}

	private static ResourceKey<Enchantment> key(String id) {
		return ResourceKey.create(Registry.ENCHANTMENT_REGISTRY, new ResourceLocation(id));
	}
}

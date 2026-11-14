package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.item.ItemStackBridge;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.19.2: an item stack answers 1.20.5's component getters from its NBT ({@link ItemStackBridge}). */
@Mixin(ItemStack.class)
abstract class ItemStackBridgeMixin implements ItemStackBridge {
}

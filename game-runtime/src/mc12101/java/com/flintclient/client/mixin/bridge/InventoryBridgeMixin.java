package com.flintclient.client.mixin.bridge;

import com.flintclient.client.compat.Bridges;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;

/** Minecraft 1.21.1: the inventory answers 1.21.5's getters ({@link Bridges.PlayerInventory}). */
@Mixin(Inventory.class)
abstract class InventoryBridgeMixin implements Bridges.PlayerInventory {
}

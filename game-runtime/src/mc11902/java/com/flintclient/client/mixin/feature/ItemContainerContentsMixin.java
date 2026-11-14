package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Containers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shulker Preview: leaves out the text list of a shulker box's items while the grid shows them. Minecraft 1.19.2 keeps a
 * box's items in its item's block entity NBT (data components came in 1.20.5) and writes the list in
 * ShulkerBoxBlock.appendHoverText, from the tag's "Items" list: the box reads as having none, so neither the item lines nor
 * the "and N more" line is written (an unknown loot table's line stays).
 */
@Mixin(ShulkerBoxBlock.class)
abstract class ItemContainerContentsMixin {
	@WrapOperation(method = "appendHoverText", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;contains(Ljava/lang/String;I)Z"))
	private boolean flint$hideList(CompoundTag tag, String key, int type, Operation<Boolean> original) {
		if ("Items".equals(key) && Mc263Containers.hideList()) {
			return false;
		}
		return original.call(tag, key, type);
	}
}

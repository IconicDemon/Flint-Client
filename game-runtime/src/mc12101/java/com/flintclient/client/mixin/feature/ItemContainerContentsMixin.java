package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Containers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shulker Preview: leaves out the text list of a shulker box's items while the grid shows them. Minecraft 1.21.1 writes that
 * list in ShulkerBoxBlock.appendHoverText (an unknown loot table's line stays).
 */
@Mixin(ShulkerBoxBlock.class)
abstract class ItemContainerContentsMixin {
	@WrapOperation(method = "appendHoverText", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/item/component/ItemContainerContents;nonEmptyItems()Ljava/lang/Iterable;"))
	private Iterable<ItemStack> flint$hideList(ItemContainerContents contents, Operation<Iterable<ItemStack>> original) {
		return Mc263Containers.hideList() ? List.of() : original.call(contents);
	}
}

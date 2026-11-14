package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inventory's open/close animations: your player's picture waits until the container is in place, as on 1.20 and later
 * (where it is drawn in a fixed screen box that can't move with the container), so the animation looks the same everywhere.
 * Minecraft 1.19.2 draws it from renderBg with renderEntityInInventory.
 */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenAnimationMixin {
	@WrapWithCondition(method = "renderBg", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;renderEntityInInventory(IIIFFLnet/minecraft/world/entity/LivingEntity;)V"))
	private boolean flint$playerWhenInPlace(int x, int y, int size, float lookX, float lookY, LivingEntity entity) {
		return !Mc263Inventory.moving();
	}
}

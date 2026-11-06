package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inventory's open/close animations: your player's picture is drawn in a fixed screen box that can't move with the
 * container, so it waits until the container is in place.
 */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenAnimationMixin {
	@WrapWithCondition(method = "extractBackground", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V"))
	private boolean flint$playerWhenInPlace(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int size, float offset, float mouseX,
		float mouseY, LivingEntity entity) {
		return !Mc263Inventory.moving();
	}
}

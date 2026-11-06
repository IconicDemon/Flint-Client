package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Nametags: your picture in the inventory (and the HUD's, which Flint draws the same way) is not you in the world, so it never
 * gets the nametag Nametags shows over you in third person (Mc263Nametags.own).
 */
@Mixin(InventoryScreen.class)
abstract class InventoryDollMixin {
	@WrapMethod(method = "extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V")
	private static void flint$picture(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int size, float offsetY, float mouseX, float mouseY,
		LivingEntity entity, Operation<Void> original) {
		Mc263Nametags.picture(true);
		try {
			original.call(graphics, x0, y0, x1, y1, size, offsetY, mouseX, mouseY, entity);
		} finally {
			Mc263Nametags.picture(false);
		}
	}
}

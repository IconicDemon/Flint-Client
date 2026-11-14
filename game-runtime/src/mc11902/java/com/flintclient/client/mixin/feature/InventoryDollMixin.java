package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Nametags: your picture in the inventory (and the HUD's, which Flint draws the same way) is not you in the world, so it never
 * gets the nametag Nametags shows over you in third person (Mc263Nametags.own). Minecraft 1.19.2 draws every such picture
 * with InventoryScreen.renderEntityInInventory, which follows the pointer itself.
 */
@Mixin(InventoryScreen.class)
abstract class InventoryDollMixin {
	@WrapMethod(method = "renderEntityInInventory(IIIFFLnet/minecraft/world/entity/LivingEntity;)V")
	private static void flint$picture(int x, int y, int size, float lookX, float lookY, LivingEntity entity, Operation<Void> original) {
		Mc263Nametags.picture(true);
		try {
			original.call(x, y, size, lookX, lookY, entity);
		} finally {
			Mc263Nametags.picture(false);
		}
	}
}

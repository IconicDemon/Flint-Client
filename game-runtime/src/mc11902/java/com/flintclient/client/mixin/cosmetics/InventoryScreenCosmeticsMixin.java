package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** While a player is drawn as a doll in a screen, what is on their head shows (even in first person). */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenCosmeticsMixin {
	@WrapMethod(method = "renderEntityInInventory")
	private static void flint$doll(int x, int y, int size, float mouseX, float mouseY, LivingEntity entity, Operation<Void> original) {
		Mc263Cosmetics.dollBegin();
		try {
			original.call(x, y, size, mouseX, mouseY, entity);
		} finally {
			Mc263Cosmetics.dollEnd();
		}
	}
}

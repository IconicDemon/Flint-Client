package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** While a player is extracted for a doll in a screen, what is on their head shows (even in first person). */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenCosmeticsMixin {
	@WrapMethod(method = "extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V")
	private static void flint$doll(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int size, float offset, float mouseX, float mouseY,
		LivingEntity entity, Operation<Void> original) {
		Mc263Cosmetics.dollBegin();
		try {
			original.call(graphics, x0, y0, x1, y1, size, offset, mouseX, mouseY, entity);
		} finally {
			Mc263Cosmetics.dollEnd();
		}
	}
}

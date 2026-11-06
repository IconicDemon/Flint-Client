package com.flintclient.legacy.mixin;

import com.flintclient.legacy.OwnNametag;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.screen.game.inventory.SurvivalInventoryScreen;
import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Nametags: your picture in the inventory (the creative inventory draws it the same way) is not you in the world, so it never
 * gets the nametag Nametags shows over you in third person (OwnNametag).
 */
@Mixin(SurvivalInventoryScreen.class)
public abstract class SurvivalInventoryScreenMixin {
	@WrapMethod(method = "renderEntity(IIIFFLnet/minecraft/entity/living/LivingEntity;)V")
	private static void flint$picture(int x, int y, int size, float mouseX, float mouseY, LivingEntity entity, Operation<Void> original) {
		OwnNametag.picture(true);
		try {
			original.call(x, y, size, mouseX, mouseY, entity);
		} finally {
			OwnNametag.picture(false);
		}
	}
}

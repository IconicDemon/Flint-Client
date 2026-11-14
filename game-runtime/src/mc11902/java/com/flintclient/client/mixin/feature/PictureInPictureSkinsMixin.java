package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3D Skins on Minecraft 1.19.2: while a menu picture of an entity (the inventory, Flint's player model) is drawn, how big its
 * pixels are on screen, so a small one keeps the flat layers whose sides couldn't be seen anyway. 1.19.2 draws such pictures
 * straight into the GUI in InventoryScreen.renderEntityInInventory, sized in GUI pixels a block.
 */
@Mixin(InventoryScreen.class)
abstract class PictureInPictureSkinsMixin {
	@Inject(method = "renderEntityInInventory(IIIFFLnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"))
	private static void flint$pictureStart(int x, int y, int size, float lookX, float lookY, LivingEntity entity, CallbackInfo callback) {
		Mc263Skins.guiPicture((float) (Minecraft.getInstance().getWindow().getGuiScale() * size));
	}

	@Inject(method = "renderEntityInInventory(IIIFFLnet/minecraft/world/entity/LivingEntity;)V", at = @At("RETURN"))
	private static void flint$pictureEnd(int x, int y, int size, float lookX, float lookY, LivingEntity entity, CallbackInfo callback) {
		Mc263Skins.guiPicture(-1);
	}
}

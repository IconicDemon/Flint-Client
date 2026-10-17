package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reach Display: the distance of each attack you make, measured as you swing (nothing about the attack changes). */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	/** Inventory: the drop key doesn't drop a protected item or the item in a locked hotbar slot. */
	@Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
	private void flint$drop(net.minecraft.client.player.LocalPlayer player, boolean fullStack, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263Inventory.blockDrop(player)) {
			callback.cancel();
		}
	}

	@Inject(method = "attack", at = @At("HEAD"))
	private void flint$attack(Player player, Entity entity, CallbackInfo callback) {
		Mc263Features.attack(player, entity);
	}
}

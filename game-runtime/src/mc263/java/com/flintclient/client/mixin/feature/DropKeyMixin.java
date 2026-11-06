package com.flintclient.client.mixin.feature;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inventory: the drop key doesn't drop a protected item or the item in a locked hotbar slot (26.3 drops through the game mode). */
@Mixin(MultiPlayerGameMode.class)
abstract class DropKeyMixin {
	@Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
	private void flint$drop(LocalPlayer player, boolean fullStack, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.Mc263Inventory.blockDrop(player)) {
			callback.cancel();
		}
	}
}

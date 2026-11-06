package com.flintclient.client.mixin.feature;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Inventory: the drop key doesn't drop a protected item or the item in a locked hotbar slot (26.2 drops through the player). */
@Mixin(LocalPlayer.class)
abstract class DropKeyMixin {
	@Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
	private void flint$drop(boolean fullStack, CallbackInfoReturnable<Boolean> callback) {
		if (com.flintclient.client.control.platform.Mc263Inventory.blockDrop((LocalPlayer) (Object) this)) {
			callback.setReturnValue(false);
		}
	}
}

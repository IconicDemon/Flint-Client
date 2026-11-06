package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263BetterF3;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom F3 (BetterF3)'s debug keys, ahead of Minecraft's. Minecraft 1.21.1 passes the key alone; the modifiers are read now. */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerF3Mixin {
	@Shadow
	protected abstract void debugFeedbackComponent(Component message);

	@Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
	private void flint$cycleDistance(int key, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263BetterF3.debugKey(key, Screen.hasControlDown(), Screen.hasShiftDown(), this::debugFeedbackComponent)) {
			callback.setReturnValue(true);
		}
	}
}

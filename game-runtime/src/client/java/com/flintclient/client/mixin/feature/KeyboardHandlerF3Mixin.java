package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263BetterF3;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom F3 (BetterF3): F3 + F cycles the render distance (with Ctrl, the simulation distance; with Shift, backwards). */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerF3Mixin {
	@Shadow
	protected abstract void debugFeedbackComponent(Component message);

	@Inject(method = "handleDebugKeys", at = @At("HEAD"), cancellable = true)
	private void flint$cycleDistance(KeyEvent event, CallbackInfoReturnable<Boolean> callback) {
		if (Mc263BetterF3.debugKey(event.key(), event.hasControlDown(), event.hasShiftDown(), this::debugFeedbackComponent)) {
			callback.setReturnValue(true);
		}
	}
}

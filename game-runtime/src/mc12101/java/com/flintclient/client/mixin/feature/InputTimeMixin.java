package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.InputTime;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.21.1: a key press or typed character counts as input for the idle frame-rate limit (compat.InputTime). */
@Mixin(KeyboardHandler.class)
abstract class InputTimeMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void flint$key(long window, int key, int scancode, int action, int modifiers, CallbackInfo callback) {
		InputTime.now();
	}

	@Inject(method = "charTyped", at = @At("HEAD"))
	private void flint$typed(long window, int codepoint, int modifiers, CallbackInfo callback) {
		InputTime.now();
	}
}

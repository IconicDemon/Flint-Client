package com.flintclient.qa.mixin;

import com.flintclient.qa.FlintQa;
import com.flintclient.qa.QaInput;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft 1.21.1: keys the tour holds read as down, as a held key would through GLFW (which can't be set). */
@Mixin(InputConstants.class)
abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true, require = 1)
	private static void flintqa$held(long window, int key, CallbackInfoReturnable<Boolean> callback) {
		if (FlintQa.ENABLED && QaInput.held(key)) {
			callback.setReturnValue(true);
		}
	}
}

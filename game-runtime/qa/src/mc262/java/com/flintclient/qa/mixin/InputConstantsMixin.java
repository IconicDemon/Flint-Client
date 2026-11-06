package com.flintclient.qa.mixin;

import com.flintclient.qa.FlintQa;
import com.flintclient.qa.QaInput;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft 26.2: keys the tour holds read as down, as a held key would through GLFW (which can't be set). */
@Mixin(InputConstants.class)
abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true, require = 1)
	private static void flintqa$held(Window window, int key, CallbackInfoReturnable<Boolean> callback) {
		if (FlintQa.ENABLED && QaInput.held(key)) {
			callback.setReturnValue(true);
		}
	}
}

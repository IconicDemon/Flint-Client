package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.InputTime;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 1.21.1: a button, a scroll or a mouse move counts as input for the idle frame-rate limit (compat.InputTime). */
@Mixin(MouseHandler.class)
abstract class MouseInputTimeMixin {
	@Inject(method = "onPress", at = @At("HEAD"))
	private void flint$button(long window, int button, int action, int modifiers, CallbackInfo callback) {
		InputTime.now();
	}

	@Inject(method = "onScroll", at = @At("HEAD"))
	private void flint$scroll(long window, double x, double y, CallbackInfo callback) {
		InputTime.now();
	}

	@Inject(method = "onMove", at = @At("HEAD"))
	private void flint$move(long window, double x, double y, CallbackInfo callback) {
		InputTime.now();
	}
}

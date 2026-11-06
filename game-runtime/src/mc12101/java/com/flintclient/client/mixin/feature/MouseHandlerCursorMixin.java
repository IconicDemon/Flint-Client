package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory's Keep cursor position (Minecraft 1.21.1): where the pointer was when a container closed, and the pointer put back
 * there when the next one opens, instead of the middle of the screen.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerCursorMixin {
	@Shadow
	private double xpos;
	@Shadow
	private double ypos;
	@Shadow
	private boolean mouseGrabbed;
	@Unique
	private final double[] flint$kept = new double[2];

	@Inject(method = "grabMouse", at = @At("HEAD"))
	private void flint$closing(CallbackInfo callback) {
		if (!mouseGrabbed) {
			Mc263Inventory.screenClosing(xpos, ypos);
		}
	}

	@WrapOperation(method = "releaseMouse", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(JIDD)V"))
	private void flint$opening(long window, int mode, double x, double y, Operation<Void> original) {
		if (Mc263Inventory.keepCursor(flint$kept)) {
			xpos = flint$kept[0];
			ypos = flint$kept[1];
			original.call(window, mode, flint$kept[0], flint$kept[1]);
		} else {
			original.call(window, mode, x, y);
		}
	}
}

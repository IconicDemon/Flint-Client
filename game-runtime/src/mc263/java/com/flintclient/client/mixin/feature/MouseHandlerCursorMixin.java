package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inventory's "Don't reset cursor": the pointer stays where it was when a screen opens right after one closed. */
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
		target = "Lcom/mojang/blaze3d/platform/InputConstants;releaseMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"))
	private void flint$opening(Window window, double x, double y, Operation<Void> original) {
		if (Mc263Inventory.keepCursor(flint$kept)) {
			xpos = flint$kept[0];
			ypos = flint$kept[1];
			original.call(window, flint$kept[0], flint$kept[1]);
		} else {
			original.call(window, x, y);
		}
	}
}

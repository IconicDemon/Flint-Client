package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom and Freelook on the mouse: while freelooking, mouse movement turns the camera instead of the player; while
 * zoomed it turns you more slowly (Dynamic sensitivity); and the wheel zooms instead of moving the hotbar while zoomed.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerFeatureMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void flint$turn(LocalPlayer player, double xo, double yo, Operation<Void> original) {
		if (Mc263Features.freelookTurn(xo, yo)) {
			return;
		}
		double k = Mc263Features.turnScale();
		original.call(player, xo * k, yo * k);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void flint$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().handle() && minecraft.gui.overlay() == null && minecraft.gui.screen() == null
			&& minecraft.player != null && Mc263Features.scroll(yoffset)) {
			callback.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void flint$tooltipScroll(long handle, double xoffset, double yoffset, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().handle() && minecraft.gui.overlay() == null && minecraft.gui.screen() != null
			&& com.flintclient.client.control.platform.Mc263Tooltips.scroll(xoffset, yoffset)) {
			callback.cancel();
		}
	}
}

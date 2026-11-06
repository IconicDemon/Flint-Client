package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.GameOptions;
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
 * zoomed it turns you more slowly (Dynamic sensitivity); and the wheel zooms instead of moving the hotbar while zoomed. A game
 * option can stop the hotbar wrapping around.
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
		original.call(player, xo, yo);
	}

	/** Zoom: the mouse sensitivity option (before Minecraft's curve) lowered with the zoom (relative sensitivity). */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "turnPlayer", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 0))
	private Object flint$zoomSensitivity(Object value) {
		return value instanceof Double d ? (Object) com.flintclient.client.control.platform.Mc263Zoom.sensitivity(d) : value;
	}

	/** Zoom: the cinematic camera while zooming with one, and always during the secondary zoom. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "turnPlayer", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/Options;smoothCamera:Z"))
	private boolean flint$zoomSmooth(boolean smooth) {
		return com.flintclient.client.control.platform.Mc263Zoom.smoothCamera(smooth);
	}

	/** Zoom: the cinematic camera glides more the higher the zoom's cinematic camera percentage. */
	@org.spongepowered.asm.mixin.injection.ModifyArg(method = "turnPlayer", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/util/SmoothDouble;getNewDeltaValue(DD)D"))
	private double flint$zoomCinematic(double smoother) {
		return com.flintclient.client.control.platform.Mc263Zoom.cinematicSmoother(smoother);
	}

	/** Zoom: the spyglass slows the mouse only when the zoom combines with it. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "turnPlayer", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;isScoping()Z"))
	private boolean flint$zoomSpyglass(boolean scoping) {
		return com.flintclient.client.control.platform.Mc263Zoom.spyglassSensitivity(scoping);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void flint$zoomScroll(long handle, double xoffset, double yoffset, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().handle() && minecraft.gui.overlay() == null && minecraft.gui.screen() == null
			&& minecraft.player != null && Mc263Features.scroll(yoffset)) {
			callback.cancel();
		}
	}

	/** Disable hotbar looping (a game option): a scroll past the first or last slot stays there instead of wrapping. */
	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
	private int flint$hotbarLooping(double scroll, int current, int size, Operation<Integer> original) {
		int next = original.call(scroll, current, size);
		next = Math.abs(next - current) > 1 && ControlState.settings().gameBool(GameOptions.NO_HOTBAR_LOOPING) ? current : next;
		// Drop Prevention: it takes a few notches to scroll off a protected item.
		return com.flintclient.client.control.platform.Mc263Tools.scroll(current, next);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void flint$tooltipScroll(long handle, double xoffset, double yoffset, CallbackInfo callback) {
		if (handle != 0L && handle == minecraft.getWindow().handle() && minecraft.gui.overlay() == null && minecraft.gui.screen() != null
			&& com.flintclient.client.control.platform.Mc263Tooltips.scroll(xoffset, yoffset)) {
			callback.cancel();
		}
	}
}

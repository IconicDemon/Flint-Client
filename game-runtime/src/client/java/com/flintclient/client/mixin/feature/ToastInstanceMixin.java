package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Toasts;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.NowPlayingToast;
import net.minecraft.client.gui.components.toasts.Toast;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toast Control on each toast: drawn where the module puts it (Minecraft's own translate swapped for its place), kept up for
 * Force Time whatever it wants, and with Top Down every toast already showing slides in again when a new one arrives.
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
abstract class ToastInstanceMixin {
	/** Toasts made so far: one more than any toast's own number means a newer one arrived. */
	@Unique
	private static int flint$made;

	@Shadow
	@Final
	private Toast toast;
	@Shadow
	@Final
	private int firstSlotIndex;
	@Shadow
	private float visiblePortion;
	@Shadow
	private long animationStartTime;
	/** This toast's number, and when it first updated (nanoseconds; 0 before). */
	@Unique
	private int flint$number;
	@Unique
	private long flint$since;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$made(CallbackInfo callback) {
		flint$number = ++flint$made;
	}

	@Inject(method = "update", at = @At("HEAD"))
	private void flint$restart(CallbackInfo callback) {
		if (flint$since == 0) {
			flint$since = System.nanoTime();
		}
		if (flint$number != flint$made && !(toast instanceof NowPlayingToast)) {
			flint$number = flint$made;
			if (Mc263Toasts.topDown()) {
				animationStartTime = -1L;
			}
		}
	}

	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/toasts/Toast;getWantedVisibility()Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;"))
	private Toast.Visibility flint$forced(Toast.Visibility wanted) {
		long forced = Mc263Toasts.forcedMillis();
		return forced > 0 && (System.nanoTime() - flint$since) / 1_000_000L <= forced ? Toast.Visibility.SHOW : wanted;
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;translate(FF)Lorg/joml/Matrix3x2f;"))
	private Matrix3x2f flint$place(Matrix3x2fStack pose, float x, float y, Operation<Matrix3x2f> original,
		@Local(argsOnly = true) int screenWidth) {
		if (toast instanceof NowPlayingToast) {
			return original.call(pose, x, y);
		}
		int newer = ((Mc263Toasts.Showing) Minecraft.getInstance().gui.toastManager()).flint$newer(this);
		Mc263Toasts.place(pose, toast, firstSlotIndex, visiblePortion, screenWidth, newer, x, y);
		return pose;
	}
}

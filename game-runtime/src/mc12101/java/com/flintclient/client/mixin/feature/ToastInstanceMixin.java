package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Toasts;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Toast Control on each toast (Minecraft 1.21.1's ToastComponent.ToastInstance, which draws its toast and works out its
 * slide in one render call): drawn where the module puts it, kept up for Force Time whatever it wants, silent when sound is
 * off, and with Top Down every toast already showing slides in again when a new one arrives.
 */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastComponent$ToastInstance")
abstract class ToastInstanceMixin {
	/** Toasts made so far: one more than any toast's own number means a newer one arrived. */
	@Unique
	private static int flint$made;

	@Shadow
	@Final
	private Toast toast;
	@Shadow
	@Final
	int index;
	@Shadow
	private long animationTime;
	@Unique
	private int flint$number;
	@Unique
	private long flint$since;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flint$made(CallbackInfo callback) {
		flint$number = ++flint$made;
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void flint$restart(int screenWidth, GuiGraphics graphics, CallbackInfoReturnable<Boolean> callback) {
		if (flint$since == 0) {
			flint$since = System.nanoTime();
		}
		if (flint$number != flint$made) {
			flint$number = flint$made;
			if (Mc263Toasts.topDown()) {
				animationTime = -1L;
			}
		}
	}

	/** Force Time: shown for at least that long, whatever the toast wants. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/toasts/Toast;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/components/toasts/ToastComponent;J)Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;"))
	private Toast.Visibility flint$forced(Toast self, GuiGraphics graphics, ToastComponent manager, long visibleMillis, Operation<Toast.Visibility> original) {
		Toast.Visibility wanted = original.call(self, graphics, manager, visibleMillis);
		long forced = Mc263Toasts.forcedMillis();
		return forced > 0 && (System.nanoTime() - flint$since) / 1_000_000L <= forced ? Toast.Visibility.SHOW : wanted;
	}

	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/toasts/Toast$Visibility;playSound(Lnet/minecraft/client/sounds/SoundManager;)V"))
	private boolean flint$sound(Toast.Visibility visibility, SoundManager sounds) {
		return Mc263Toasts.sound();
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void flint$place(PoseStack stack, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) int screenWidth,
		@Local(argsOnly = true) GuiGraphics graphics) {
		int newer = ((Mc263Toasts.Showing) Minecraft.getInstance().getToasts()).flint$newer(this);
		float portion = toast.width() == 0 ? 1.0F : (screenWidth - x) / toast.width();
		Mc263Toasts.place(graphics.pose2d(), toast, index, portion, screenWidth, newer, x, y);
		original.call(stack, 0.0F, 0.0F, z);
	}
}

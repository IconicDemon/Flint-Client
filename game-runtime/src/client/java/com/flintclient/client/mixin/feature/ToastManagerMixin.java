package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Toasts;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.List;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.sounds.SoundEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toast Control: blocked toasts are never queued, a toast that appears is silent when sound is off, and as many toasts show
 * at once as Toast Count says (Minecraft's 5 slots otherwise).
 */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin implements Mc263Toasts.Showing {
	@Shadow
	@Final
	private List<?> visibleToasts;

	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void flint$filter(Toast toast, CallbackInfo callback) {
		if (!Mc263Toasts.allow(toast)) {
			callback.cancel();
		}
	}

	// The queue's lambda in update() that shows a waiting toast and plays its sound.
	@ModifyExpressionValue(method = "lambda$update$1", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/toasts/Toast;getSoundEvent()Lnet/minecraft/sounds/SoundEvent;"))
	private @Nullable SoundEvent flint$sound(@Nullable SoundEvent sound) {
		return Mc263Toasts.sound() ? sound : null;
	}

	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = {"freeSlotCount", "findFreeSlotsIndex"}, at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "intValue=5"), require = 0)
	private int flint$slots(int vanilla) {
		return Mc263Toasts.slots(vanilla);
	}

	@Override
	public int flint$newer(Object instance) {
		int at = visibleToasts.indexOf(instance);
		return at < 0 ? 0 : visibleToasts.size() - 1 - at;
	}
}

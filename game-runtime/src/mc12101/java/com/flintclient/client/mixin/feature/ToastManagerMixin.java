package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Toasts;
import java.util.List;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toast Control on Minecraft 1.21.1's ToastComponent (what 1.21.2 renamed ToastManager): blocked toasts are never queued,
 * and as many toasts show at once as Toast Count says (Minecraft's 5 slots otherwise). The sound is ToastInstanceMixin's.
 */
@Mixin(ToastComponent.class)
abstract class ToastManagerMixin implements Mc263Toasts.Showing {
	@Shadow
	@Final
	private List<?> visible;

	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void flint$filter(Toast toast, CallbackInfo callback) {
		if (!Mc263Toasts.allow(toast)) {
			callback.cancel();
		}
	}

	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = {"freeSlots", "findFreeIndex"}, at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "intValue=5"), require = 0)
	private int flint$slots(int vanilla) {
		return Mc263Toasts.slots(vanilla);
	}

	@Override
	public int flint$newer(Object instance) {
		int at = visible.indexOf(instance);
		return at < 0 ? 0 : visible.size() - 1 - at;
	}
}

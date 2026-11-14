package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.v1190.ShowingToasts;
import com.flintclient.client.control.platform.Mc263Toasts;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toast Control on Minecraft 1.19 (1.19.0)'s ToastComponent: blocked toasts are never queued. 1.19.0 shows toasts in five fixed
 * slots (an array; 1.19.1 made them a list with as many slots as asked), so Toast Count can't change how many show; which came
 * after which is kept by {@link ShowingToasts}, emptied here with the slots. The sound is ToastInstanceMixin's.
 */
@Mixin(ToastComponent.class)
abstract class ToastManagerMixin implements Mc263Toasts.Showing {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void flint$filter(Toast toast, CallbackInfo callback) {
		if (!Mc263Toasts.allow(toast)) {
			callback.cancel();
		}
	}

	@Inject(method = "clear", at = @At("HEAD"))
	private void flint$cleared(CallbackInfo callback) {
		ShowingToasts.cleared();
	}

	@Override
	public int flint$newer(Object instance) {
		return ShowingToasts.newer(instance);
	}
}

package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Chat Reports' Hide warning toast: no "Chat messages can't be verified" toast.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ToastManager.class)
abstract class NcrToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void flint$unverifiedToast(Toast toast, CallbackInfo callback) {
		if (toast instanceof SystemToast system && system.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING
			&& Mc263ChatSafety.with(NoChatReports.HIDE_TOAST)) {
			callback.cancel();
		}
	}
}

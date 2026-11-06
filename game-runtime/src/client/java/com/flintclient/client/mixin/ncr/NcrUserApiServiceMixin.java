package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import com.mojang.authlib.minecraft.TelemetrySession;
import java.util.concurrent.Executor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Chat Reports' Disable telemetry: authlib's telemetry sessions are the disabled one.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(targets = McCompat.USER_API_SERVICE_CLASS, remap = false)
abstract class NcrUserApiServiceMixin {
	@Inject(method = "newTelemetrySession", at = @At("HEAD"), cancellable = true, remap = false)
	private void flint$telemetry(Executor executor, CallbackInfoReturnable<TelemetrySession> callback) {
		if (Mc263ChatSafety.with(NoChatReports.DISABLE_TELEMETRY)) {
			callback.setReturnValue(TelemetrySession.DISABLED);
		}
	}
}

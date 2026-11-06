package com.flintclient.client.thirdparty.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Walksy Lib, when installed: no "&lt;mod&gt; &lt;version&gt; is out. Open the WalksyLib panel to install it." toast at start-up
 * (it covered the top right of Flint's main menu). Walksy Lib has no setting for it; only that toast goes: the update check
 * still runs and the update is still listed in Walksy Lib's own panel, ready to install, and every other Walksy Lib toast
 * (an update installed or failed) is shown as before. Walksy Lib 2.x calls {@code WalksyLib.notifyOutdated(ModUpdate)} once
 * per outdated mod; if a later release renames it, this does nothing.
 */
@Pseudo
@Mixin(targets = "main.walksy.lib.core.WalksyLib", remap = false)
abstract class WalksyUpdateNoticeMixin {
	@Inject(method = "notifyOutdated", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void flint$noUpdateToast(CallbackInfo callback) {
		callback.cancel();
	}
}

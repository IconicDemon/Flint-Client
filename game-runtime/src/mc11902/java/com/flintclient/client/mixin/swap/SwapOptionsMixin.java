package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap's standby game never writes options.txt while it waits: the player's running game owns that file, and the
 * standby's own values (its GUI scale fitted to its hidden small window, its fullscreen off) must not replace the player's.
 * It writes them as usual once it has taken over.
 */
@Mixin(Options.class)
abstract class SwapOptionsMixin {
	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void flint$notWhileStandby(CallbackInfo callback) {
		if (InstantSwap.waiting()) {
			callback.cancel();
		}
	}
}

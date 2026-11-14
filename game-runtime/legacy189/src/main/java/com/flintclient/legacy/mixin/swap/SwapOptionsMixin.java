package com.flintclient.legacy.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.options.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant Swap's standby game never writes options.txt while it waits: the player's running game owns that file. It writes it
 * as usual once it has taken over.
 */
@Mixin(GameOptions.class)
abstract class SwapOptionsMixin {
	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void flint$notWhileStandby(CallbackInfo ci) {
		if (InstantSwap.waiting()) {
			ci.cancel();
		}
	}
}

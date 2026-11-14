package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instant Swap's standby game is silent (no menu music over the player's game) until it takes over (1.21.1's play returns nothing). */
@Mixin(SoundManager.class)
abstract class SwapSoundMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void flint$silentWhileStandby(SoundInstance sound, CallbackInfo callback) {
		if (InstantSwap.silent()) {
			callback.cancel();
		}
	}
}

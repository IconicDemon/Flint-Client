package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Instant Swap's standby game is silent (no menu music over the player's game) until it takes over. */
@Mixin(SoundManager.class)
abstract class SwapSoundMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void flint$silentWhileStandby(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
		if (InstantSwap.silent()) {
			callback.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}

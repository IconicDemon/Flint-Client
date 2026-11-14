package com.flintclient.legacy.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Instant Swap's standby game is silent (no menu music over the player's game) until it takes over. */
@Mixin(SoundEngine.class)
abstract class SwapSoundMixin {
	@Inject(method = "play(Lnet/minecraft/client/sound/instance/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
	private void flint$silentWhileStandby(SoundInstance sound, CallbackInfo ci) {
		if (InstantSwap.silent()) {
			ci.cancel();
		}
	}
}

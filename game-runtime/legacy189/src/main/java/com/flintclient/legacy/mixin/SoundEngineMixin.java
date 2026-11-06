package com.flintclient.legacy.mixin;

import com.flintclient.legacy.mods.Subtitles;
import net.minecraft.client.sound.instance.SoundInstance;
import net.minecraft.client.sound.system.SoundEngine;
import net.minecraft.client.sound.system.SoundManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Subtitles' Nearby Sound Activity on 1.8.9: each sound as the engine is asked to play it (delayed ones when their time comes),
 * before its volume checks, so the list shows sounds with the game muted too. Only reads: what plays, and how, is untouched.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@Shadow
	@Final
	private SoundManager manager;

	@Inject(method = "play(Lnet/minecraft/client/sound/instance/SoundInstance;)V", at = @At("HEAD"))
	private void flint$heard(SoundInstance sound, CallbackInfo callback) {
		if (sound != null) {
			Subtitles.heard(sound, manager.get(sound.getLocation()));
		}
	}
}

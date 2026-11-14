package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.sound.SoundPhysics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sound Physics: your own sounds follow you (Minecraft plays them where you were when they started), so their echo is
 * worked out from where you are. Minecraft 1.19.2's level has no "play a local sound on this entity" (1.20 added it), so the
 * sound that call makes, one bound to you, is played here. Adapted from Sound Physics Remastered's {@code LocalPlayerMixin}
 * (GPL-3.0).
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerSoundMixin {
	@Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
	private void flint$followMe(SoundEvent sound, float volume, float pitch, CallbackInfo callback) {
		if (SoundPhysics.on()) {
			LocalPlayer self = (LocalPlayer) (Object) this;
			callback.cancel();
			Minecraft.getInstance().getSoundManager().play(new EntityBoundSoundInstance(sound, self.getSoundSource(), volume, pitch, self,
				self.level.random.nextLong()));
		}
	}
}

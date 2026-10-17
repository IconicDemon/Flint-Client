package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Sounds;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Better Sounds: each sound's volume times its category's and its own setting, when it starts and as it plays on. */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@WrapOperation(method = "play", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
	private float flint$startVolume(SoundEngine engine, float volume, SoundSource source, Operation<Float> original,
		@Local(argsOnly = true) SoundInstance instance) {
		return original.call(engine, volume, source) * Mc263Sounds.factor(instance);
	}

	@ModifyReturnValue(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"))
	private float flint$volume(float volume, @Local(argsOnly = true) SoundInstance instance) {
		return volume * Mc263Sounds.factor(instance);
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.ambient.AmbientPlayback;
import com.flintclient.client.control.platform.sound.SoundPhysics;
import com.flintclient.client.control.platform.sound.TaggedChannel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Channel;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's sound mods in Minecraft's sound engine: each new channel learns which sound and category it plays before it is
 * set up (Sound Physics), a sound engine start remakes Sound Physics' effects, and an Ambient Sounds loop that is streamed
 * starts at a random point.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineTagMixin {
	@Inject(method = "loadLibrary", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Listener;reset()V"))
	private void flint$libraryLoaded(CallbackInfo callback) {
		SoundPhysics.libraryLoaded();
		com.flintclient.client.control.platform.ambient.AmbientSounds.soundsReloaded();
	}

	@WrapOperation(method = "play", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V", ordinal = 0))
	private void flint$tag(ChannelAccess.ChannelHandle handle, Consumer<Channel> setup, Operation<Void> original,
		@Local(argsOnly = true) SoundInstance instance) {
		Identifier sound = instance.getIdentifier();
		SoundSource category = instance.getSource();
		boolean offset = AmbientPlayback.randomOffset(instance);
		original.call(handle, (Consumer<Channel>) channel -> {
			((TaggedChannel) channel).flint$tag(sound, category, offset);
			setup.accept(channel);
		});
	}

	@WrapOperation(method = "play", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/Identifier;Z)Ljava/util/concurrent/CompletableFuture;"))
	private CompletableFuture<AudioStream> flint$streamOffset(SoundBufferLibrary buffers, Identifier path, boolean looping,
		Operation<CompletableFuture<AudioStream>> original, @Local(argsOnly = true) SoundInstance instance) {
		CompletableFuture<AudioStream> stream = original.call(buffers, path, looping);
		return AmbientPlayback.randomOffset(instance) ? AmbientPlayback.skipped(stream, path) : stream;
	}
}

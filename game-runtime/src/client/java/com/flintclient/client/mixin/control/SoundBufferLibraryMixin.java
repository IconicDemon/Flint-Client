package com.flintclient.client.mixin.control;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlCenter;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supplies the samples of Flint's generated interface sounds ({@code UiSounds}) where Minecraft would decode
 * an Ogg file. The buffer goes into Minecraft's own cache, so later plays reuse it and the engine frees it
 * with the rest (on a sound reload or device change), after which the next play makes a fresh one. Other
 * namespaces, and Flint files that aren't generated sounds, go on to vanilla untouched, as does every file
 * once the sounds have failed ({@link ControlCenter#soundBuffer} guards them). Runs when a sound starts
 * playing, never per frame.
 */
@Mixin(SoundBufferLibrary.class)
abstract class SoundBufferLibraryMixin {
	@Shadow
	@Final
	private Map<Identifier, CompletableFuture<SoundBuffer>> cache;

	@Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true)
	private void flint$generatedSound(Identifier location, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> callback) {
		if (!FlintClient.MOD_ID.equals(location.getNamespace())) {
			return;
		}
		CompletableFuture<SoundBuffer> buffer = cache.get(location);
		if (buffer == null) {
			buffer = ControlCenter.soundBuffer(location);
			if (buffer == null) {
				return;
			}
			cache.put(location, buffer);
		}
		callback.setReturnValue(buffer);
	}
}

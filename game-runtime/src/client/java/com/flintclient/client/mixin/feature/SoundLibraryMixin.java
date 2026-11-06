package com.flintclient.client.mixin.feature;

import com.mojang.blaze3d.audio.Library;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.EXTEfx;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sound Physics: Minecraft's OpenAL context asks for four auxiliary sends per sound (OpenAL Soft gives two unless asked),
 * one for each of Sound Physics' reverbs. Always asked, so turning Sound Physics on later needs no sound restart; a
 * context with sends nobody uses costs nothing. Adapted from Sound Physics Remastered's {@code LibraryMixin} (GPL-3.0).
 */
@Mixin(Library.class)
abstract class SoundLibraryMixin {
	@ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"), index = 1)
	private IntBuffer flint$auxSends(IntBuffer attributes) {
		if (attributes == null) {
			return null;
		}
		IntBuffer original = attributes.duplicate();
		int count = original.remaining();
		IntBuffer out = BufferUtils.createIntBuffer(count + 3);
		// Every key/value pair up to the terminating 0, then the sends, then the 0.
		for (int i = 0; i + 1 < count; i += 2) {
			int key = original.get(original.position() + i);
			if (key == 0) {
				break;
			}
			if (key != EXTEfx.ALC_MAX_AUXILIARY_SENDS) {
				out.put(key).put(original.get(original.position() + i + 1));
			}
		}
		out.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4).put(0);
		return out.flip();
	}
}

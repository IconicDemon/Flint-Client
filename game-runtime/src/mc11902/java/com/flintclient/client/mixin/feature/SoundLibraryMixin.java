package com.flintclient.client.mixin.feature;

import com.mojang.blaze3d.audio.Library;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTHRTF;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Sound Physics: Minecraft's OpenAL context asks for four auxiliary sends per sound (OpenAL Soft gives two unless asked),
 * one for each of Sound Physics' reverbs. Always asked, so turning Sound Physics on later needs no sound restart; a
 * context with sends nobody uses costs nothing. Adapted from Sound Physics Remastered's {@code LibraryMixin} (GPL-3.0).
 *
 * <p>Minecraft 1.19.2 makes its context with no attribute list at all (later versions pass one), which leaves the device as
 * Directional Audio has just set it (Library.setHrtf). A list makes OpenAL Soft work the device's settings out again from
 * it, so the one made here carries the device's HRTF state along, and Directional Audio stays as the player set it.
 */
@Mixin(Library.class)
abstract class SoundLibraryMixin {
	@ModifyArg(method = "init", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"), index = 1)
	private IntBuffer flint$auxSends(long device, IntBuffer attributes) {
		if (attributes == null) {
			IntBuffer out = BufferUtils.createIntBuffer(5);
			if (ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF") && ALC10.alcGetInteger(device, SOFTHRTF.ALC_NUM_HRTF_SPECIFIERS_SOFT) > 0) {
				out.put(SOFTHRTF.ALC_HRTF_SOFT).put(ALC10.alcGetInteger(device, SOFTHRTF.ALC_HRTF_SOFT));
			}
			out.put(EXTEfx.ALC_MAX_AUXILIARY_SENDS).put(4).put(0);
			return out.flip();
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

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.sound.SoundPhysics;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Sound Physics' sound distance allowance: how far away a sound may be and still be sent to you, times Minecraft's 16
 * blocks. The server decides that, so it takes effect in singleplayer (the built-in server runs in this game). Adapted
 * from Sound Physics Remastered's {@code SoundEventMixin} (GPL-3.0).
 */
@Mixin(SoundEvent.class)
abstract class SoundEventRangeMixin {
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "getRange", at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "floatValue=16.0"), require = 0, expect = 2)
	private float flint$allowance(float range) {
		return SoundPhysics.rangeAllowance(range);
	}
}

package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Zoom;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.SpyglassItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Zoom and the spyglass (Zoomify): the spyglass's sound when raised follows the zoom's sound rule (as in Zoomify, only the
 * sound of raising it; lowering plays through another call).
 */
@Mixin(SpyglassItem.class)
abstract class SpyglassZoomMixin {
	@WrapWithCondition(method = "use", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/Player;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
	private boolean flint$spyglassSound(Player player, SoundEvent sound, float volume, float pitch) {
		return Mc263Zoom.spyglassSound();
	}
}
